import java.util.Random;

/**
 * ChaosEngine - simulates an unreliable network by randomly "dropping" packets.
 * The chaos factor determines the probability that a packet will be lost.
 * A chaos factor of 0.0 means no packets are lost (fully reliable).
 * A chaos factor of 1.0 means all packets are lost.
 */
public class ChaosEngine {
    
    private double chaosFactor;
    private Random random;
    private int totalPackets;
    private int droppedPackets;

    /**
     * Creates a new ChaosEngine with the given chaos factor.
     * @param chaosFactor probability of dropping a packet (0.0 to 1.0)
     */
    public ChaosEngine(double chaosFactor) {
        if (chaosFactor < 0.0 || chaosFactor > 1.0) {
            throw new IllegalArgumentException("Chaos factor must be between 0.0 and 1.0");
        }
        this.chaosFactor = chaosFactor;
        this.random = new Random();
        this.totalPackets = 0;
        this.droppedPackets = 0;
    }

    /**
     * Decides whether a packet should be "sent" or "dropped".
     * Returns true if the packet should be sent (not dropped).
     * Returns false if the packet should be dropped (lost).
     */
    public boolean sendPacket() {
        totalPackets++;
        if (random.nextDouble() < chaosFactor) {
            droppedPackets++;
            return false; // packet is dropped
        }
        return true; // packet goes through
    }

    public double getChaosFactor() {
        return chaosFactor;
    }

    public int getTotalPackets() {
        return totalPackets;
    }

    public int getDroppedPackets() {
        return droppedPackets;
    }

    public double getActualLossRate() {
        if (totalPackets == 0) return 0.0;
        return (double) droppedPackets / totalPackets;
    }

    /**
     * Reset the counters
     */
    public void reset() {
        totalPackets = 0;
        droppedPackets = 0;
    }

    @Override
    public String toString() {
        return "ChaosEngine[factor=" + chaosFactor + ", total=" + totalPackets 
               + ", dropped=" + droppedPackets + "]";
    }
}
