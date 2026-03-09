import java.util.ArrayList;
import java.util.List;

// ChaosEngine - handles packet permutation and ACK dropping
public class ChaosEngine {

    // Permute packets for GBN: (i, i+1, i+2, i+3) -> (i+2, i, i+3, i+1)
    public static List<DSPacket> permutePackets(List<DSPacket> windowGroup) {

        // Only permute groups of 4
        if (windowGroup.size() != 4) {
            return windowGroup;
        }

        List<DSPacket> shuffled = new ArrayList<>(4);

        shuffled.add(windowGroup.get(2));
        shuffled.add(windowGroup.get(0));
        shuffled.add(windowGroup.get(3));
        shuffled.add(windowGroup.get(1));

        return shuffled;
    }

    // Check if ACK should be dropped (every RN-th ACK is dropped)
    public static boolean shouldDrop(int ackCount, int rn) {

        // RN = 0 means no drops
        if (rn <= 0) {
            return false;
        }

        // Drop every rn-th ACK
        return (ackCount % rn == 0);
    }
}
