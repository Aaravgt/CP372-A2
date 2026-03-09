import java.io.*;
import java.net.*;
import java.util.*;

// Sender - sends file to receiver using UDP
public class Sender {

    private static final int MAX_TIMEOUTS = 3;

    private String receiverIP;
    private int receiverDataPort;
    private int senderAckPort;
    private String inputFile;
    private int timeoutMs;
    private int windowSize;
    private boolean isGBN;

    private DatagramSocket ackSocket;
    private DatagramSocket sendSocket;
    private InetAddress receiverAddress;

    private List<DSPacket> dataPackets;
    private long startTime;

    public static void main(String[] args) {
        if (args.length < 5) {
            System.out.println("Usage: java Sender <rcv_ip> <rcv_data_port> <sender_ack_port> <input_file> <timeout_ms> [window_size]");
            return;
        }

        try {
            String rcvIP = args[0];
            int rcvDataPort = Integer.parseInt(args[1]);
            int senderAckPort = Integer.parseInt(args[2]);
            String inputFile = args[3];
            int timeoutMs = Integer.parseInt(args[4]);
            int windowSize = 1;

            if (args.length >= 6) {
                windowSize = Integer.parseInt(args[5]);
            }

            Sender sender = new Sender(rcvIP, rcvDataPort, senderAckPort, inputFile, timeoutMs, windowSize);
            sender.run();
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public Sender(String rcvIP, int rcvDataPort, int senderAckPort, String inputFile, int timeoutMs, int windowSize) {
        this.receiverIP = rcvIP;
        this.receiverDataPort = rcvDataPort;
        this.senderAckPort = senderAckPort;
        this.inputFile = inputFile;
        this.timeoutMs = timeoutMs;
        this.windowSize = windowSize;
        this.isGBN = (windowSize > 1);
    }

    public void run() throws Exception {
        receiverAddress = InetAddress.getByName(receiverIP);
        ackSocket = new DatagramSocket(senderAckPort);
        sendSocket = new DatagramSocket();
        ackSocket.setSoTimeout(timeoutMs);

        System.out.println("=== DS-FTP Sender ===");
        System.out.println("Mode: " + (isGBN ? "Go-Back-N (N=" + windowSize + ")" : "Stop-and-Wait"));
        System.out.println("Receiver: " + receiverIP + ":" + receiverDataPort);
        System.out.println("ACK Port: " + senderAckPort);
        System.out.println("Timeout: " + timeoutMs + "ms");

        // Read and prepare data packets
        byte[] fileData = readFile(inputFile);
        System.out.println("File: " + inputFile + " (" + fileData.length + " bytes)");

        dataPackets = createDataPackets(fileData);
        System.out.println("Data packets: " + dataPackets.size());

        // Phase 1: Handshake
        startTime = System.currentTimeMillis();
        if (!performHandshake()) {
            System.out.println("Unable to transfer file.");
            cleanup();
            return;
        }

        // Phase 2: Data Transfer
        boolean success;
        if (isGBN) {
            success = sendGBN();
        } else {
            success = sendStopAndWait();
        }

        if (!success) {
            System.out.println("Unable to transfer file.");
            cleanup();
            return;
        }

        // Phase 3: Teardown
        if (!performTeardown()) {
            System.out.println("Unable to transfer file.");
            cleanup();
            return;
        }

        long endTime = System.currentTimeMillis();
        double totalTime = (endTime - startTime) / 1000.0;
        System.out.printf("Total Transmission Time: %.2f seconds%n", totalTime);

        cleanup();
    }

    private boolean performHandshake() throws Exception {
        DSPacket sot = new DSPacket(DSPacket.TYPE_SOT, 0, null);
        int timeoutCount = 0;

        while (timeoutCount < MAX_TIMEOUTS) {
            System.out.println("[SEND] SOT seq=0");
            sendPacket(sot);

            try {
                DSPacket ack = receiveACK();
                if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == 0) {
                    System.out.println("[RECV] ACK seq=0 - Handshake complete");
                    return true;
                }
            } catch (SocketTimeoutException e) {
                timeoutCount++;
                System.out.println("[TIMEOUT] SOT (" + timeoutCount + "/" + MAX_TIMEOUTS + ")");
            }
        }
        return false;
    }

    private boolean sendStopAndWait() throws Exception {
        for (int i = 0; i < dataPackets.size(); i++) {
            DSPacket packet = dataPackets.get(i);
            int expectedSeq = packet.getSeqNum();
            int timeoutCount = 0;

            while (timeoutCount < MAX_TIMEOUTS) {
                System.out.println("[SEND] DATA seq=" + expectedSeq + " len=" + packet.getLength());
                sendPacket(packet);

                try {
                    DSPacket ack = receiveACK();
                    if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == expectedSeq) {
                        System.out.println("[RECV] ACK seq=" + ack.getSeqNum());
                        timeoutCount = 0;
                        break;
                    } else {
                        System.out.println("[RECV] ACK seq=" + ack.getSeqNum() + " (expected " + expectedSeq + ")");
                    }
                } catch (SocketTimeoutException e) {
                    timeoutCount++;
                    System.out.println("[TIMEOUT] DATA seq=" + expectedSeq + " (" + timeoutCount + "/" + MAX_TIMEOUTS + ")");
                }
            }

            if (timeoutCount >= MAX_TIMEOUTS) {
                return false;
            }
        }
        return true;
    }

    private boolean sendGBN() throws Exception {
        int base = 0;
        int nextSeq = 0;
        int total = dataPackets.size();
        int timeoutCount = 0;

        while (base < total) {
            // Send packets in window
            List<DSPacket> toSend = new ArrayList<>();
            while (nextSeq < base + windowSize && nextSeq < total) {
                toSend.add(dataPackets.get(nextSeq));
                nextSeq++;
            }

            // Apply chaos permutation for groups of 4
            List<DSPacket> permuted = new ArrayList<>();
            int idx = 0;
            while (idx < toSend.size()) {
                int remaining = toSend.size() - idx;
                if (remaining >= 4) {
                    List<DSPacket> group = toSend.subList(idx, idx + 4);
                    permuted.addAll(ChaosEngine.permutePackets(group));
                    idx += 4;
                } else {
                    permuted.addAll(toSend.subList(idx, toSend.size()));
                    break;
                }
            }

            // Send permuted packets
            for (DSPacket p : permuted) {
                System.out.println("[SEND] DATA seq=" + p.getSeqNum() + " len=" + p.getLength());
                sendPacket(p);
            }

            // Wait for ACKs
            try {
                DSPacket ack = receiveACK();
                if (ack.getType() == DSPacket.TYPE_ACK) {
                    int ackSeq = ack.getSeqNum();
                    System.out.println("[RECV] ACK seq=" + ackSeq);

                    // Calculate new base from cumulative ACK
                    int newBase = seqToIndex(ackSeq) + 1;
                    if (newBase > base) {
                        base = newBase;
                        timeoutCount = 0;
                    }
                }
            } catch (SocketTimeoutException e) {
                timeoutCount++;
                System.out.println("[TIMEOUT] base=" + base + " (" + timeoutCount + "/" + MAX_TIMEOUTS + ")");
                if (timeoutCount >= MAX_TIMEOUTS) {
                    return false;
                }
                nextSeq = base; // Go back N
            }
        }
        return true;
    }

    private int seqToIndex(int seq) {
        // Map sequence number back to packet index
        // First DATA packet has seq=1, index=0
        for (int i = 0; i < dataPackets.size(); i++) {
            if (dataPackets.get(i).getSeqNum() == seq) {
                return i;
            }
        }
        return -1;
    }

    private boolean performTeardown() throws Exception {
        int eotSeq;
        if (dataPackets.isEmpty()) {
            eotSeq = 1;
        } else {
            eotSeq = (dataPackets.get(dataPackets.size() - 1).getSeqNum() + 1) % 128;
        }

        DSPacket eot = new DSPacket(DSPacket.TYPE_EOT, eotSeq, null);
        int timeoutCount = 0;

        while (timeoutCount < MAX_TIMEOUTS) {
            System.out.println("[SEND] EOT seq=" + eotSeq);
            sendPacket(eot);

            try {
                DSPacket ack = receiveACK();
                if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == eotSeq) {
                    System.out.println("[RECV] ACK seq=" + ack.getSeqNum() + " - Transfer complete");
                    return true;
                }
            } catch (SocketTimeoutException e) {
                timeoutCount++;
                System.out.println("[TIMEOUT] EOT (" + timeoutCount + "/" + MAX_TIMEOUTS + ")");
            }
        }
        return false;
    }

    private void sendPacket(DSPacket packet) throws Exception {
        byte[] data = packet.toBytes();
        DatagramPacket udp = new DatagramPacket(data, data.length, receiverAddress, receiverDataPort);
        sendSocket.send(udp);
    }

    private DSPacket receiveACK() throws Exception {
        byte[] buffer = new byte[DSPacket.MAX_PACKET_SIZE];
        DatagramPacket udp = new DatagramPacket(buffer, buffer.length);
        ackSocket.receive(udp);
        return new DSPacket(udp.getData());
    }

    private byte[] readFile(String filename) throws IOException {
        File f = new File(filename);
        byte[] data = new byte[(int) f.length()];
        try (FileInputStream fis = new FileInputStream(f)) {
            fis.read(data);
        }
        return data;
    }

    private List<DSPacket> createDataPackets(byte[] fileData) {
        List<DSPacket> packets = new ArrayList<>();
        int offset = 0;
        int seq = 1;

        while (offset < fileData.length) {
            int len = Math.min(DSPacket.MAX_PAYLOAD_SIZE, fileData.length - offset);
            byte[] chunk = Arrays.copyOfRange(fileData, offset, offset + len);
            packets.add(new DSPacket(DSPacket.TYPE_DATA, seq, chunk));
            offset += len;
            seq = (seq + 1) % 128;
        }

        return packets;
    }

    private void cleanup() {
        if (ackSocket != null) ackSocket.close();
        if (sendSocket != null) sendSocket.close();
    }
}
