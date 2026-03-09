import java.io.*;
import java.net.*;
import java.util.*;

/**
 * Sender - reads a file and sends it to the Receiver using UDP.
 * Supports two protocols:
 *   - Stop-and-Wait (SAW): window size = 1
 *   - Go-Back-N (GBN): window size = N
 * 
 * Usage: java Sender <receiverHost> <receiverPort> <inputFile> <protocol> <windowSize> <timeout> <chaosFactor>
 *   protocol: SAW or GBN
 *   windowSize: only used for GBN (SAW always uses 1)
 *   timeout: in milliseconds
 *   chaosFactor: 0.0 to 1.0
 */
public class Sender {

    // max payload per data packet (in bytes)
    private static final int MAX_PAYLOAD = 512;

    private String receiverHost;
    private int receiverPort;
    private String inputFile;
    private String protocol; // "SAW" or "GBN"
    private int windowSize;
    private int timeout;
    private ChaosEngine chaos;

    private DatagramSocket socket;
    private InetAddress receiverAddress;

    // stats
    private int totalPacketsSent = 0;
    private int totalRetransmissions = 0;
    private int totalACKsReceived = 0;
    private long startTime;
    private long endTime;

    public Sender(String host, int port, String file, String protocol, 
                  int windowSize, int timeout, double chaosFactor) {
        this.receiverHost = host;
        this.receiverPort = port;
        this.inputFile = file;
        this.protocol = protocol.toUpperCase();
        this.windowSize = (this.protocol.equals("SAW")) ? 1 : windowSize;
        this.timeout = timeout;
        this.chaos = new ChaosEngine(chaosFactor);
    }

    /**
     * Main method to run the sender
     */
    public void run() throws Exception {
        socket = new DatagramSocket();
        socket.setSoTimeout(timeout);
        receiverAddress = InetAddress.getByName(receiverHost);

        System.out.println("=== Sender started ===");
        System.out.println("Protocol: " + protocol + ", Window: " + windowSize 
                         + ", Timeout: " + timeout + "ms, Chaos: " + chaos.getChaosFactor());
        System.out.println("Sending file: " + inputFile + " to " + receiverHost + ":" + receiverPort);

        // read file into byte array
        byte[] fileData = readFile(inputFile);
        System.out.println("File size: " + fileData.length + " bytes");

        // split file into chunks
        List<byte[]> chunks = splitData(fileData);
        System.out.println("Total data packets to send: " + chunks.size());

        // Step 1: send SOT (handshake)
        startTime = System.currentTimeMillis();
        sendSOT();

        // Step 2: send data using the selected protocol
        if (protocol.equals("SAW")) {
            sendStopAndWait(chunks);
        } else {
            sendGoBackN(chunks);
        }

        // Step 3: send EOT (teardown)
        sendEOT(chunks.size());
        endTime = System.currentTimeMillis();

        // print stats
        printStats(chunks.size());

        socket.close();
        System.out.println("=== Sender finished ===");
    }

    /**
     * Send SOT packet and wait for ACK (3-way handshake start)
     */
    private void sendSOT() throws Exception {
        DSPacket sotPacket = new DSPacket(DSPacket.SOT, 0);
        boolean acked = false;

        while (!acked) {
            System.out.println("[SEND] SOT packet");
            sendPacket(sotPacket);
            totalPacketsSent++;

            try {
                DSPacket ack = receivePacket();
                if (ack != null && ack.getType() == DSPacket.ACK && ack.getAckNum() == 0) {
                    System.out.println("[RECV] SOT ACK received");
                    totalACKsReceived++;
                    acked = true;
                }
            } catch (SocketTimeoutException e) {
                System.out.println("[TIMEOUT] SOT - retransmitting...");
                totalRetransmissions++;
            }
        }
    }

    /**
     * Send EOT packet and wait for ACK
     */
    private void sendEOT(int lastSeq) throws Exception {
        DSPacket eotPacket = new DSPacket(DSPacket.EOT, lastSeq + 1);
        boolean acked = false;

        while (!acked) {
            System.out.println("[SEND] EOT packet (seq=" + (lastSeq + 1) + ")");
            sendPacket(eotPacket);
            totalPacketsSent++;

            try {
                DSPacket ack = receivePacket();
                if (ack != null && ack.getType() == DSPacket.ACK) {
                    System.out.println("[RECV] EOT ACK received");
                    totalACKsReceived++;
                    acked = true;
                }
            } catch (SocketTimeoutException e) {
                System.out.println("[TIMEOUT] EOT - retransmitting...");
                totalRetransmissions++;
            }
        }
    }

    /**
     * Stop-and-Wait protocol: send one packet, wait for ACK, then send next
     */
    private void sendStopAndWait(List<byte[]> chunks) throws Exception {
        for (int i = 0; i < chunks.size(); i++) {
            DSPacket dataPacket = new DSPacket(DSPacket.DATA, i + 1, chunks.get(i));
            boolean acked = false;

            while (!acked) {
                System.out.println("[SEND] DATA packet seq=" + (i + 1) + " (" + chunks.get(i).length + " bytes)");
                sendPacket(dataPacket);
                totalPacketsSent++;

                try {
                    DSPacket ack = receivePacket();
                    if (ack != null && ack.getType() == DSPacket.ACK && ack.getAckNum() == i + 1) {
                        System.out.println("[RECV] ACK for seq=" + (i + 1));
                        totalACKsReceived++;
                        acked = true;
                    } else if (ack != null) {
                        System.out.println("[RECV] Unexpected ACK ack=" + ack.getAckNum() + " (expected " + (i + 1) + ")");
                    }
                } catch (SocketTimeoutException e) {
                    System.out.println("[TIMEOUT] seq=" + (i + 1) + " - retransmitting...");
                    totalRetransmissions++;
                }
            }
        }
    }

    /**
     * Go-Back-N protocol: send multiple packets within window, slide on cumulative ACK
     */
    private void sendGoBackN(List<byte[]> chunks) throws Exception {
        int base = 1;          // first unACKed packet
        int nextSeqNum = 1;    // next packet to send
        int totalChunks = chunks.size();

        while (base <= totalChunks) {
            // send all packets in the window that haven't been sent yet
            while (nextSeqNum < base + windowSize && nextSeqNum <= totalChunks) {
                DSPacket dataPacket = new DSPacket(DSPacket.DATA, nextSeqNum, chunks.get(nextSeqNum - 1));
                System.out.println("[SEND] DATA packet seq=" + nextSeqNum + " (window: " + base + "-" + Math.min(base + windowSize - 1, totalChunks) + ")");
                sendPacket(dataPacket);
                totalPacketsSent++;
                nextSeqNum++;
            }

            // wait for ACK
            try {
                DSPacket ack = receivePacket();
                if (ack != null && ack.getType() == DSPacket.ACK) {
                    int ackNum = ack.getAckNum();
                    System.out.println("[RECV] ACK for seq=" + ackNum);
                    totalACKsReceived++;

                    // cumulative ACK - slide window
                    if (ackNum >= base) {
                        base = ackNum + 1;
                    }
                }
            } catch (SocketTimeoutException e) {
                // timeout - go back to base and resend everything in the window
                System.out.println("[TIMEOUT] base=" + base + " - going back to retransmit window...");
                totalRetransmissions++;
                nextSeqNum = base; // go back N
            }
        }
    }

    /**
     * Send a packet through the chaos engine (may be dropped)
     */
    private void sendPacket(DSPacket packet) throws Exception {
        if (chaos.sendPacket()) {
            byte[] data = packet.toBytes();
            DatagramPacket udpPacket = new DatagramPacket(data, data.length, receiverAddress, receiverPort);
            socket.send(udpPacket);
        } else {
            System.out.println("  >> [CHAOS] Packet seq=" + packet.getSeqNum() + " DROPPED by sender chaos engine");
        }
    }

    /**
     * Receive a packet (ACK) from the receiver
     */
    private DSPacket receivePacket() throws Exception {
        byte[] buffer = new byte[4096];
        DatagramPacket udpPacket = new DatagramPacket(buffer, buffer.length);
        socket.receive(udpPacket);
        byte[] received = Arrays.copyOf(udpPacket.getData(), udpPacket.getLength());
        return DSPacket.fromBytes(received);
    }

    /**
     * Read entire file into byte array
     */
    private byte[] readFile(String filename) throws IOException {
        File f = new File(filename);
        byte[] data = new byte[(int) f.length()];
        FileInputStream fis = new FileInputStream(f);
        fis.read(data);
        fis.close();
        return data;
    }

    /**
     * Split data into chunks of MAX_PAYLOAD size
     */
    private List<byte[]> splitData(byte[] data) {
        List<byte[]> chunks = new ArrayList<>();
        int offset = 0;
        while (offset < data.length) {
            int len = Math.min(MAX_PAYLOAD, data.length - offset);
            byte[] chunk = Arrays.copyOfRange(data, offset, offset + len);
            chunks.add(chunk);
            offset += len;
        }
        return chunks;
    }

    /**
     * Print transmission statistics
     */
    private void printStats(int totalDataPackets) {
        long duration = endTime - startTime;
        System.out.println("\n--- Transmission Statistics ---");
        System.out.println("Protocol:          " + protocol);
        System.out.println("Window size:       " + windowSize);
        System.out.println("Timeout:           " + timeout + " ms");
        System.out.println("Chaos factor:      " + chaos.getChaosFactor());
        System.out.println("Data packets:      " + totalDataPackets);
        System.out.println("Total sent:        " + totalPacketsSent);
        System.out.println("Retransmissions:   " + totalRetransmissions);
        System.out.println("ACKs received:     " + totalACKsReceived);
        System.out.println("Transfer time:     " + duration + " ms");
        if (duration > 0) {
            System.out.println("Throughput:        " + String.format("%.2f", (totalDataPackets * MAX_PAYLOAD * 8.0) / (duration / 1000.0)) + " bps");
        }
        System.out.println("Chaos engine:      " + chaos);
        System.out.println("-------------------------------");
    }

    public static void main(String[] args) {
        if (args.length < 7) {
            System.out.println("Usage: java Sender <receiverHost> <receiverPort> <inputFile> <protocol> <windowSize> <timeout> <chaosFactor>");
            System.out.println("  protocol: SAW or GBN");
            System.out.println("  windowSize: window size for GBN (ignored for SAW)");
            System.out.println("  timeout: timeout in milliseconds");
            System.out.println("  chaosFactor: 0.0 to 1.0 (probability of packet loss)");
            System.out.println("Example: java Sender localhost 9876 input.txt GBN 4 500 0.1");
            return;
        }

        try {
            String host = args[0];
            int port = Integer.parseInt(args[1]);
            String file = args[2];
            String protocol = args[3];
            int window = Integer.parseInt(args[4]);
            int timeout = Integer.parseInt(args[5]);
            double chaosFactor = Double.parseDouble(args[6]);

            Sender sender = new Sender(host, port, file, protocol, window, timeout, chaosFactor);
            sender.run();
        } catch (NumberFormatException e) {
            System.out.println("Error: Invalid number format in arguments.");
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
