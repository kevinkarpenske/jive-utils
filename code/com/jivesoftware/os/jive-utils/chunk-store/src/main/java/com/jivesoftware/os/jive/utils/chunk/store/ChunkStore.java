package com.jivesoftware.os.jive.utils.chunk.store;

import com.jivesoftware.os.jive.utils.io.Filer;
import com.jivesoftware.os.jive.utils.io.FilerIO;
import com.jivesoftware.os.jive.utils.io.SubsetableFiler;


public class ChunkStore {

    private static final long cMagicNumber = Long.MAX_VALUE;
    private static final int cMinPower = 8;

    private long lengthOfFile = 8 + 8 + (8 * (64 - cMinPower));
    private long referenceNumber = 0;

    private SubsetableFiler filer;

    /*
     New Call Sequence
     ChunkStore chunks = ChunkStore();
     chunks.setup(100);
     open(_filer);
     */
    public ChunkStore() {
    }

    /*
     * file header format
     * lengthOfFile
     * referenceNumber
     * free 2^8
     * free 2^9
     * thru
     * free 2^64
     */
    public void setup(long _referenceNumber) {
        lengthOfFile = 8 + 8 + (8 * (64 - cMinPower));
        referenceNumber = _referenceNumber;
    }

    public long sizeInBytes() {
        //TODO
        return 0;
    }

    public long bytesNeeded() {
        return Long.MAX_VALUE;
    }

    public void open(SubsetableFiler _filer) throws Exception {
        filer = _filer;
        synchronized (filer.lock()) {
            FilerIO.writeLong(filer, lengthOfFile, "lengthOfFile");
            FilerIO.writeLong(filer, referenceNumber, "referenceNumber");
            for (int i = cMinPower; i < 65; i++) {
                FilerIO.writeLong(filer, -1, "free");
            }
            filer.flush();
        }
    }

    /*
     Exsisting Call Sequence
     ChunkStore chunks = ChunkStore(_filer);
     open();
     */
    public ChunkStore(SubsetableFiler _filer) throws Exception {
        filer = _filer;
    }

    public void open() throws Exception {
        synchronized (filer.lock()) {
            filer.seek(0);
            lengthOfFile = FilerIO.readLong(filer, "lengthOfFile");
            referenceNumber = FilerIO.readLong(filer, "referenceNumber");
        }
    }

    public long getReferenceNumber() {
        return referenceNumber;
    }

    public void allChunks(ChunkIdStream _chunks) throws Exception {
        synchronized (filer.lock()) {
            filer.seek(8 + 8 + (8 * (64 - cMinPower)));
            long size = filer.getSize();
            while (filer.getFilePointer() < size) {
                long chunkFP = filer.getFilePointer();
                long magicNumber = FilerIO.readLong(filer, "magicNumber");
                if (magicNumber != cMagicNumber) {
                    throw new Exception("Invalid chunkFP " + chunkFP);
                }
                long chunkPower = FilerIO.readLong(filer, "chunkPower");
                FilerIO.readLong(filer, "chunkNexFreeChunkFP");
                long chunkLength = FilerIO.readLong(filer, "chunkLength");
                long fp = filer.getFilePointer();
                if (chunkLength > 0) {
                    long more = _chunks.stream(chunkFP);
                    if (more != chunkFP) {
                        break;
                    }
                }
                filer.seek(fp + FilerIO.chunkLength(chunkPower));
            }
        }
    }

    public long newChunk(long _chunkLength) throws Exception {
        long resultFP;
        synchronized (filer.lock()) {
            long chunkPower = FilerIO.chunkPower(_chunkLength, cMinPower);
            resultFP = resuseChunk(chunkPower);
            if (resultFP == -1) {
                long chunkLength = FilerIO.chunkLength(chunkPower);
                chunkLength += 8; // add magicNumber
                chunkLength += 8; // add chunkPower
                chunkLength += 8; // add next free chunk of equal size
                chunkLength += 8; // add bytesLength
                long newChunkFP = lengthOfFile;
                if (newChunkFP + chunkLength > filer.endOfFP()) {
                    //!! to do over flow allocated chunk request reallocation
                    throw new RuntimeException("need larger allocation for ChunkFile" + this);
                }
                synchronized (filer.lock()) {
                    filer.seek(newChunkFP + chunkLength - 1); // last byte in chunk
                    filer.write(0); // cause file backed ChunkStore to grow file on disk. Use setLength()?
                    filer.seek(newChunkFP);
                    FilerIO.writeLong(filer, cMagicNumber, "magicNumber");
                    FilerIO.writeLong(filer, chunkPower, "chunkPower");
                    FilerIO.writeLong(filer, -1, "chunkNexFreeChunkFP");
                    FilerIO.writeLong(filer, _chunkLength, "chunkLength");
                    lengthOfFile += chunkLength;
                    filer.seek(0);
                    FilerIO.writeLong(filer, lengthOfFile, "lengthOfFile");
                    filer.flush();
                }
                return newChunkFP;
            }
        }
        return resultFP;
    }

    private long resuseChunk(long _chunkPower) throws Exception {
        synchronized (filer.lock()) {
            filer.seek(freeSeek(_chunkPower));
            long reuseFP = FilerIO.readLong(filer, "free");
            if (reuseFP == -1) {
                return reuseFP;
            }
            long nextFree = readNextFree(reuseFP);
            filer.seek(freeSeek(_chunkPower));
            FilerIO.writeLong(filer, nextFree, "free");
            return reuseFP;
        }
    }

    public Filer getFiler(long _chunkFP) throws Exception {
        long chunkPower = 0;
        long nextFreeChunkFP = 0;
        long length = 0;
        long fp = 0;
        synchronized (filer.lock()) {
            filer.seek(_chunkFP);
            long magicNumber = FilerIO.readLong(filer, "magicNumber");
            if (magicNumber != cMagicNumber) {
                throw new Exception("Invalid chunkFP " + _chunkFP);
            }
            chunkPower = FilerIO.readLong(filer, "chunkPower");
            nextFreeChunkFP = FilerIO.readLong(filer, "chunkNexFreeChunkFP");
            length = FilerIO.readLong(filer, "chunkLength");
            fp = filer.getFilePointer();
        }

        try {
            return filer.get(fp, fp + FilerIO.chunkLength(chunkPower), length);
        } catch (Exception x) {
            x.printStackTrace();
            System.out.println("_chunkFP=" + _chunkFP);
            System.out.println("nextFree=" + nextFreeChunkFP);
            System.out.println("fp=" + fp);
            System.out.println("length=" + length);
            System.out.println("chunkPower=" + chunkPower);
            throw x;
        }
    }

    public void remove(long _chunkFP) throws Exception {
        synchronized (filer.lock()) {
            filer.seek(_chunkFP);
            long magicNumber = FilerIO.readLong(filer, "magicNumber");
            if (magicNumber != cMagicNumber) {
                throw new Exception("Invalid chunkFP " + _chunkFP);
            }
            long chunkPower = FilerIO.readLong(filer, "chunkPower");
            FilerIO.readLong(filer, "chunkNexFreeChunkFP");
            FilerIO.writeLong(filer, -1, "chunkLength");
            long chunkLength = FilerIO.chunkLength(chunkPower); // bytes
            // fill with zeros
            while (chunkLength >= zerosMax.length) {
                filer.write(zerosMax);
                chunkLength -= zerosMax.length;
            }
            while (chunkLength >= zerosMin.length) {
                filer.write(zerosMin);
                chunkLength -= zerosMin.length;
            }
            filer.flush();
            // save as free chunk
            filer.seek(freeSeek(chunkPower));
            long freeFP = FilerIO.readLong(filer, "free");
            if (freeFP == -1) {
                filer.seek(freeSeek(chunkPower));
                FilerIO.writeLong(filer, _chunkFP, "free");
                filer.flush();
            } else {
                long nextFree = readNextFree(freeFP);
                filer.seek(freeSeek(chunkPower));
                FilerIO.writeLong(filer, _chunkFP, "free");
                writeNextFree(_chunkFP, nextFree);
                filer.flush();
            }
        }
    }

    private static final byte[] zerosMin = new byte[(int) Math.pow(2, cMinPower)]; // never too big
    private static final byte[] zerosMax = new byte[(int) Math.pow(2, 16)]; // 65536 max used until min needed

    private long freeSeek(long _chunkPower) {
        return 8 + 8 + ((_chunkPower - cMinPower) * 8);
    }

    private long readNextFree(long _chunkFP) throws Exception {
        synchronized (filer.lock()) {
            filer.seek(_chunkFP);
            FilerIO.readLong(filer, "magicNumber");
            FilerIO.readLong(filer, "chunkPower");
            return FilerIO.readLong(filer, "chunkNexFreeChunkFP");
        }
    }

    private void writeNextFree(long _chunkFP, long _nextFreeFP) throws Exception {
        synchronized (filer.lock()) {
            filer.seek(_chunkFP);
            FilerIO.readLong(filer, "magicNumber");
            FilerIO.readLong(filer, "chunkPower");
            FilerIO.writeLong(filer, _nextFreeFP, "chunkNexFreeChunkFP");
        }
    }

//    public static String name(String _chunkName) {
//        String fileName = _chunkName + ".chunk";
//        ensureDirectory(new File(fileName));
//        return fileName;
//    }
//
//    public static ChunkStore factory(String _chunkName) throws Exception {
//        if (new File(name(_chunkName)).exists()) {
//            return openInstance(_chunkName);
//        }
//        return newInstance(_chunkName);
//    }
//
//    public static ChunkStore newInstance(String _chunkName) throws Exception {
//        Filer chunkFiler = Filer.open(name(_chunkName), "rw");
//        SubsetableFiler chunkSegment = new SubsetableFiler(chunkFiler, 0, Long.MAX_VALUE, 0);
//        ChunkStore chunks = new ChunkStore();
//        chunks.open(chunkSegment);
//        return chunks;
//    }
//
//    public static ChunkStore openInstance(String _chunkName) throws Exception {
//        Filer chunkFiler = Filer.open(name(_chunkName), "rw");
//        SubsetableFiler chunkSegment = new SubsetableFiler(chunkFiler, 0, Long.MAX_VALUE, 0);
//        ChunkStore chunks = new ChunkStore(chunkSegment);
//        chunks.open();
//        return chunks;
//    }

}