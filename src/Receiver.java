import java.io.*;
import java.net.*;
import java.util.*;

/**
 * Receiver - listens for packets from the Sender and writes the received data to a file.
 * Sends ACKs back to the Sender. Works with both SAW and GBN protocols.
 * 
 * Usage: java Receiver <port> <outputFile> <chaosFactor>
 */
public class Receiver {

    private int port;
    private String outputFile;
    private ChaosEngine chaos;
    private DatagramSocket socket;

    // store received data in order
    private Map<Integer, byte[]> receivedData;
    private int expectedSeqNum;

    // stats
    private int totalPacketsReceived = 0;
    private int totalACKsSent = 0;
    private int duplicatePackets = 0;
    private long startTime;
    private long endTime;

    public Receiver(int port, String outputFile, double chaosFactor) {
        this.port = port;
        this.outputFile = outputFile;
        this.chaos = new ChaosEngine(chaosFactor);
        this.receivedData = new TreeMap<>();
        this.expectedSeqNum = 1; // first data packet has seq=1
    }

    /**
     * Main method to run the receiver - listens for packets until EOT
     */
    public void run() throws Exception {
        socket = new DatagramSocket(port);
        // set a long timeout so we don't hang forever if sender dies
        socket.setSoTimeout(30000);

        System.out.println("=== Receiver started on port " + port + " ===");
        System.out.println("Output file: " + outputFile);
        System.out.println("Chaos factor: " + chaos.getChaosFactor());
        System.out.println("Waiting for connection...\n");

        boolean running = true;

        while (running) {
            try {
                // receive a packet
                byte[] buffer = new byte[4096];
                DatagramPacket udpPacket = new DatagramPacket(buffer, buffer.length);
                socket.receive(udpPacket);

                byte[] received = Arrays.copyOf(udpPacket.getData(), udpPacket.getLength());
                DSPacket packet = DSPacket.fromBytes(received);
                
                if (packet == null) {
                    System.out.println("[ERROR] Could not deserialize packet, ignoring.");
                    continue;
                }

                totalPacketsReceived++;
                InetAddress senderAddr = udpPacket.getAddress();
                int senderPort = udpPacket.getPort();

                switch (packet.getType()) {
                    case DSPacket.SOT:
                        handleSOT(packet, senderAddr, senderPort);
                        break;

                    case DSPacket.DATA:
                        handleData(packet, senderAddr, senderPort);
                        break;

                    case DSPacket.EOT:
                        handleEOT(packet, senderAddr, senderPort);
                        running = false; // we're done
                        break;

                    default:
                        System.out.println("[RECV] Unknown packet type: " + packet.getType());
                }

            } catch (SocketTimeoutException e) {
                System.out.println("[TIMEOUT] No packet received for 30 seconds. Shutting down.");
                running = false;
            }
        }

        endTime = System.currentTimeMillis();

        // write received data to file
        writeOutputFile();

        // print stats
        printStats();

        socket.close();
        System.out.println("=== Receiver finished ===");
    }

    /**
     * Handle SOT (Start of Transmission) packet - send back ACK
     */
    private void handleSOT(DSPacket packet, InetAddress addr, int port) throws Exception {
        System.out.println("[RECV] SOT packet received - connection established");
        startTime = System.currentTimeMillis();

        // send ACK for SOT
        DSPacket ack = new DSPacket(DSPacket.ACK, 0, 0);
        sendACK(ack, addr, port);
    }

    /**
     * Handle DATA packet - buffer data, send ACK  
     * Uses cumulative ACK approach for GBN compatibility
     */
    private void handleData(DSPacket packet, InetAddress addr, int port) throws Exception {
        int seqNum = packet.getSeqNum();
        System.out.println("[RECV] DATA packet seq=" + seqNum + " (" + packet.getData().length + " bytes)" 
                         + " | expected=" + expectedSeqNum);

        if (seqNum == expectedSeqNum) {
            // this is the packet we expected - store it and advance
            receivedData.put(seqNum, packet.getData());
            expectedSeqNum++;

            // also check if we have any buffered out-of-order packets (not needed for GBN but just in case)
            while (receivedData.containsKey(expectedSeqNum)) {
                expectedSeqNum++;
            }

            // send cumulative ACK for the highest in-order packet received
            DSPacket ack = new DSPacket(DSPacket.ACK, seqNum, expectedSeqNum - 1);
            System.out.println("[SEND] ACK for seq=" + (expectedSeqNum - 1));
            sendACK(ack, addr, port);

        } else if (seqNum < expectedSeqNum) {
            // duplicate packet - resend ACK for last in-order packet
            duplicatePackets++;
            System.out.println("[RECV] Duplicate packet seq=" + seqNum + " (already received)");
            DSPacket ack = new DSPacket(DSPacket.ACK, seqNum, expectedSeqNum - 1);
            System.out.println("[SEND] ACK for seq=" + (expectedSeqNum - 1) + " (duplicate)");
            sendACK(ack, addr, port);

        } else {
            // out-of-order (ahead of expected) - for GBN we discard and resend last ACK
            System.out.println("[RECV] Out-of-order packet seq=" + seqNum + " (expected " + expectedSeqNum + "), discarding");
            // send ACK for last correctly received packet
            DSPacket ack = new DSPacket(DSPacket.ACK, seqNum, expectedSeqNum - 1);
            System.out.println("[SEND] ACK for seq=" + (expectedSeqNum - 1) + " (cumulative)");
            sendACK(ack, addr, port);
        }
    }

    /**
     * Handle EOT (End of Transmission) packet - send back ACK
     */
    private void handleEOT(DSPacket packet, InetAddress addr, int port) throws Exception {
        System.out.println("[RECV] EOT packet received - transmission complete");
        
        // send ACK for EOT
        DSPacket ack = new DSPacket(DSPacket.ACK, packet.getSeqNum(), packet.getSeqNum());
        sendACK(ack, addr, port);
    }

    /**
     * Send an ACK packet through the chaos engine (may be dropped)
     */
    private void sendACK(DSPacket ack, InetAddress addr, int port) throws Exception {
        totalACKsSent++;
        if (chaos.sendPacket()) {
            byte[] data = ack.toBytes();
            DatagramPacket udpPacket = new DatagramPacket(data, data.length, addr, port);
            socket.send(udpPacket);
        } else {
            System.out.println("  >> [CHAOS] ACK DROPPED by receiver chaos engine");
        }
    }

    /**
     * Write all received data to the output file, in order
     */
    private void writeOutputFile() throws IOException {
        FileOutputStream fos = new FileOutputStream(outputFile);
        for (Map.Entry<Integer, byte[]> entry : receivedData.entrySet()) {
            fos.write(entry.getValue());
        }
        fos.close();
        System.out.println("\nFile written to: " + outputFile);
        System.out.println("Total data packets assembled: " + receivedData.size());
    }

    /**
     * Print receiver statistics
     */
    private void printStats() {
        long duration = endTime - startTime;
        System.out.println("\n--- Receiver Statistics ---");
        System.out.println("Total packets received: " + totalPacketsReceived);
        System.out.println("Total ACKs sent:        " + totalACKsSent);
        System.out.println("Duplicate packets:      " + duplicatePackets);
        System.out.println("Transfer time:          " + duration + " ms");
        System.out.println("Chaos engine:           " + chaos);
        System.out.println("---------------------------");
    }

    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("Usage: java Receiver <port> <outputFile> <chaosFactor>");
            System.out.println("  port: port number to listen on");
            System.out.println("  outputFile: file to write received data to");
            System.out.println("  chaosFactor: 0.0 to 1.0 (probability of ACK loss)");
            System.out.println("Example: java Receiver 9876 output.txt 0.1");
            return;
        }

        try {
            int port = Integer.parseInt(args[0]);
            String outputFile = args[1];
            double chaosFactor = Double.parseDouble(args[2]);

            Receiver receiver = new Receiver(port, outputFile, chaosFactor);
            receiver.run();
        } catch (NumberFormatException e) {
            System.out.println("Error: Invalid number format in arguments.");
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
