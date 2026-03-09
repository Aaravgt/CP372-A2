import java.nio.ByteBuffer;

// DSPacket - 128 byte packet format for DS-FTP protocol
public class DSPacket {

    // Fixed packet sizes
    public static final int MAX_PACKET_SIZE  = 128;
    public static final int MAX_PAYLOAD_SIZE = 124;

    // Packet types
    public static final byte TYPE_SOT  = 0;
    public static final byte TYPE_DATA = 1;
    public static final byte TYPE_ACK  = 2;
    public static final byte TYPE_EOT  = 3;

    // Header fields
    private byte type;
    private byte seqNum;
    private short length;   // Payload length in bytes (0–124)
    private byte[] payload;

    // Constructor for sending packets
    public DSPacket(byte type, int seqNum, byte[] data) {
        this.type = type;
        this.seqNum = (byte) (seqNum % 128);

        // Control packets should have no payload
        this.payload = (data != null) ? data : new byte[0];
        this.length = (short) this.payload.length;
    }

    // Constructor for parsing received packets
    public DSPacket(byte[] rawBytes) {

        ByteBuffer bb = ByteBuffer.wrap(rawBytes);

        this.type   = bb.get();
        this.seqNum = bb.get();
        this.length = bb.getShort();

        // Defensive validation of payload length
        if (this.length < 0 || this.length > MAX_PAYLOAD_SIZE) {
            throw new IllegalArgumentException(
                "Invalid payload length: " + this.length
            );
        }

        this.payload = new byte[this.length];
        bb.get(this.payload);
    }

    // Convert packet to 128 byte array
    public byte[] toBytes() {

        ByteBuffer bb = ByteBuffer.allocate(MAX_PACKET_SIZE);

        bb.put(type);
        bb.put(seqNum);
        bb.putShort(length);
        bb.put(payload);

        // Remaining bytes are automatically zero-filled
        return bb.array();
    }

    // Getters

    public byte getType() {
        return type;
    }

    // Get sequence number as unsigned int
    public int getSeqNum() {
        return seqNum & 0xFF;
    }

    public int getLength() {
        return length;
    }

    public byte[] getPayload() {
        return payload;
    }
}
