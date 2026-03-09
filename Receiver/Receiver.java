import java.io.*;
import java.net.*;
import java.util.*;

/**
 * DS-FTP Receiver - Earth Station
 * Receives files from Mars Rover (Sender) using Stop-and-Wait or Go-Back-N protocol.
 * 
 * Usage: java Receiver <sender_ip> <sender_ack_port> <rcv_data_port> <output_file> <RN>
 *   - RN: Reliability Number (0 = no ACK loss, X = every Xth ACK dropped)
 */
public class Receiver {

    private String senderIP;
    private int senderAckPort;
    private int rcvDataPort;
    private String outputFile;
    private int rn;

    private DatagramSocket dataSocket;
    private InetAddress senderAddress;

    private int expectedSeq;
    private Map<Integer, byte[]> buffer;  // For GBN buffering
    private int windowSize;
    private int ackCount;
    private int lastDeliveredSeq;

    public static void main(String[] args) {
        if (args.length < 5) {
            System.out.println("Usage: java Receiver <sender_ip> <sender_ack_port> <rcv_data_port> <output_file> <RN>");
            return;
        }

        try {
            String senderIP = args[0];
            int senderAckPort = Integer.parseInt(args[1]);
            int rcvDataPort = Integer.parseInt(args[2]);
            String outputFile = args[3];
            int rn = Integer.parseInt(args[4]);

            Receiver receiver = new Receiver(senderIP, senderAckPort, rcvDataPort, outputFile, rn);
            receiver.run();
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public Receiver(String senderIP, int senderAckPort, int rcvDataPort, String outputFile, int rn) {
        this.senderIP = senderIP;
        this.senderAckPort = senderAckPort;
        this.rcvDataPort = rcvDataPort;
        this.outputFile = outputFile;
        this.rn = rn;
        this.expectedSeq = 1;
        this.buffer = new HashMap<>();
        this.windowSize = 128;  // Max window, will work for both SAW and GBN
        this.ackCount = 0;
        this.lastDeliveredSeq = 0;
    }

    public void run() throws Exception {
        senderAddress = InetAddress.getByName(senderIP);
        dataSocket = new DatagramSocket(rcvDataPort);

        System.out.println("=== DS-FTP Receiver ===");
        System.out.println("Listening on port: " + rcvDataPort);
        System.out.println("Sender ACK port: " + senderAckPort);
        System.out.println("Output file: " + outputFile);
        System.out.println("Reliability Number (RN): " + rn);
        System.out.println("Waiting for connection...\n");

        List<byte[]> receivedData = new ArrayList<>();
        boolean running = true;

        while (running) {
            byte[] buf = new byte[DSPacket.MAX_PACKET_SIZE];
            DatagramPacket udp = new DatagramPacket(buf, buf.length);
            dataSocket.receive(udp);

            DSPacket packet = new DSPacket(udp.getData());
            int type = packet.getType();
            int seq = packet.getSeqNum();

            switch (type) {
                case DSPacket.TYPE_SOT:
                    System.out.println("[RECV] SOT seq=" + seq);
                    sendACK(0);
                    expectedSeq = 1;
                    lastDeliveredSeq = 0;
                    buffer.clear();
                    receivedData.clear();
                    break;

                case DSPacket.TYPE_DATA:
                    System.out.println("[RECV] DATA seq=" + seq + " len=" + packet.getLength() + " (expected=" + expectedSeq + ")");
                    handleData(packet, receivedData);
                    break;

                case DSPacket.TYPE_EOT:
                    System.out.println("[RECV] EOT seq=" + seq);
                    sendACK(seq);
                    running = false;
                    break;

                default:
                    System.out.println("[RECV] Unknown packet type: " + type);
            }
        }

        // Write output file
        writeFile(receivedData);
        dataSocket.close();
        System.out.println("=== Receiver finished ===");
    }

    private void handleData(DSPacket packet, List<byte[]> receivedData) throws Exception {
        int seq = packet.getSeqNum();

        if (seq == expectedSeq) {
            // In-order packet - deliver immediately
            receivedData.add(packet.getPayload());
            lastDeliveredSeq = seq;
            expectedSeq = (expectedSeq + 1) % 128;

            // Deliver any buffered packets in order
            while (buffer.containsKey(expectedSeq)) {
                receivedData.add(buffer.remove(expectedSeq));
                lastDeliveredSeq = expectedSeq;
                expectedSeq = (expectedSeq + 1) % 128;
            }

            sendACK(lastDeliveredSeq);

        } else if (isDuplicate(seq)) {
            // Duplicate packet (already delivered) - resend last ACK
            System.out.println("  Duplicate seq=" + seq + ", resending ACK");
            sendACK(lastDeliveredSeq);

        } else if (isInWindow(seq)) {
            // Out-of-order but within window - buffer it
            if (!buffer.containsKey(seq)) {
                buffer.put(seq, packet.getPayload());
                System.out.println("  Buffered seq=" + seq);
            }
            // Send cumulative ACK for last delivered
            sendACK(lastDeliveredSeq);

        } else {
            // Outside window - discard and resend last ACK
            System.out.println("  Outside window, discarding");
            sendACK(lastDeliveredSeq);
        }
    }

    private boolean isDuplicate(int seq) {
        // Check if seq is "behind" expectedSeq (mod 128 wraparound)
        // A packet is duplicate if it's in the range [lastDeliveredSeq - windowSize + 1, lastDeliveredSeq]
        int diff = (expectedSeq - seq + 128) % 128;
        return diff > 0 && diff <= windowSize;
    }

    private boolean isInWindow(int seq) {
        // Check if seq is within [expectedSeq, expectedSeq + windowSize - 1] mod 128
        int start = expectedSeq;
        int end = (expectedSeq + windowSize - 1) % 128;

        if (start <= end) {
            return seq >= start && seq <= end;
        } else {
            return seq >= start || seq <= end;
        }
    }

    private void sendACK(int seq) throws Exception {
        ackCount++;
        
        if (ChaosEngine.shouldDrop(ackCount, rn)) {
            System.out.println("[DROP] ACK seq=" + seq + " (ackCount=" + ackCount + ", RN=" + rn + ")");
            return;
        }

        DSPacket ack = new DSPacket(DSPacket.TYPE_ACK, seq, null);
        byte[] data = ack.toBytes();
        DatagramPacket udp = new DatagramPacket(data, data.length, senderAddress, senderAckPort);
        dataSocket.send(udp);
        System.out.println("[SEND] ACK seq=" + seq);
    }

    private void writeFile(List<byte[]> data) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            for (byte[] chunk : data) {
                fos.write(chunk);
            }
        }
        System.out.println("File written: " + outputFile);
        
        // Calculate total size
        int totalSize = 0;
        for (byte[] chunk : data) {
            totalSize += chunk.length;
        }
        System.out.println("Total bytes: " + totalSize);
    }
}
