import java.io.Serializable;

/**
 * DSPacket - represents a single packet in our reliable data transfer protocol.
 * Each packet has a type, sequence number, and optional data payload.
 */
public class DSPacket implements Serializable {

    private static final long serialVersionUID = 1L;

    // packet types
    public static final int SOT = 0;   // start of transmission
    public static final int EOT = 1;   // end of transmission
    public static final int DATA = 2;  // data packet
    public static final int ACK = 3;   // acknowledgment

    private int type;
    private int seqNum;
    private int ackNum;
    private byte[] data;
    private long timestamp; // when packet was created/sent

    // constructor for control packets (SOT, EOT, ACK)
    public DSPacket(int type, int seqNum) {
        this.type = type;
        this.seqNum = seqNum;
        this.ackNum = -1;
        this.data = new byte[0];
        this.timestamp = System.nanoTime();
    }

    // constructor for data packets
    public DSPacket(int type, int seqNum, byte[] data) {
        this.type = type;
        this.seqNum = seqNum;
        this.ackNum = -1;
        this.data = data;
        this.timestamp = System.nanoTime();
    }

    // constructor for ACK packets
    public DSPacket(int type, int seqNum, int ackNum) {
        this.type = type;
        this.seqNum = seqNum;
        this.ackNum = ackNum;
        this.data = new byte[0];
        this.timestamp = System.nanoTime();
    }

    // getters and setters
    public int getType() { return type; }
    public void setType(int type) { this.type = type; }

    public int getSeqNum() { return seqNum; }
    public void setSeqNum(int seqNum) { this.seqNum = seqNum; }

    public int getAckNum() { return ackNum; }
    public void setAckNum(int ackNum) { this.ackNum = ackNum; }

    public byte[] getData() { return data; }
    public void setData(byte[] data) { this.data = data; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    // helper to get packet type as a string for logging
    public String getTypeString() {
        switch (type) {
            case SOT: return "SOT";
            case EOT: return "EOT";
            case DATA: return "DATA";
            case ACK: return "ACK";
            default: return "UNKNOWN";
        }
    }

    @Override
    public String toString() {
        return "[" + getTypeString() + " seq=" + seqNum + " ack=" + ackNum 
               + " dataLen=" + (data != null ? data.length : 0) + "]";
    }

    // serialize packet to byte array for sending over UDP
    public byte[] toBytes() {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(bos);
            oos.writeObject(this);
            oos.flush();
            return bos.toByteArray();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // deserialize packet from byte array
    public static DSPacket fromBytes(byte[] bytes) {
        try {
            java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(bytes);
            java.io.ObjectInputStream ois = new java.io.ObjectInputStream(bis);
            return (DSPacket) ois.readObject();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
