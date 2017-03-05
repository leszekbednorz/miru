package org.roaringbitmap;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.primitives.Shorts;
import com.jivesoftware.os.miru.plugin.bitmap.CardinalityAndLastSetBit;
import com.jivesoftware.os.miru.plugin.bitmap.MiruBitmaps.StreamAtoms;
import com.jivesoftware.os.miru.plugin.index.BitmapAndLastId;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 *
 */
public class RoaringInspection {

    public static RoaringBitmap[] split(RoaringBitmap bitmap) {
        RoaringArray array = bitmap.highLowContainer;
        int size = array.size();
        RoaringBitmap[] split = new RoaringBitmap[size];
        for (int i = 0; i < size; i++) {
            split[i] = new RoaringBitmap();
            split[i].highLowContainer.append(array.getKeyAtIndex(i), array.getContainerAtIndex(i));
        }
        return split;
    }

    public static RoaringBitmap join(RoaringBitmap[] split) {
        RoaringBitmap bitmap = new RoaringBitmap();
        RoaringArray array = bitmap.highLowContainer;
        array.extendArray(split.length);
        for (int i = 0; i < split.length; i++) {
            array.append(split[i].highLowContainer.getKeyAtIndex(0), split[i].highLowContainer.getContainerAtIndex(0));
        }
        return bitmap;
    }

    public static RoaringBitmap[] extract(RoaringBitmap bitmap, int[] ukeys) {
        RoaringArray array = bitmap.highLowContainer;
        short[] keys = intToShortKeys(ukeys);
        RoaringBitmap[] extract = new RoaringBitmap[keys.length];
        for (int i = 0; i < keys.length; i++) {
            Container container = array.getContainer(keys[i]);
            if (container != null) {
                extract[i] = new RoaringBitmap();
                extract[i].highLowContainer.append(keys[i], container);
            }
        }
        return extract;
    }

    public static CardinalityAndLastSetBit<RoaringBitmap> cardinalityAndLastSetBit(RoaringBitmap bitmap) {
        int pos = bitmap.highLowContainer.size() - 1;
        int lastSetBit = -1;
        while (pos >= 0) {
            Container lastContainer = bitmap.highLowContainer.values[pos];
            lastSetBit = lastSetBit(bitmap.highLowContainer.getKeyAtIndex(pos), lastContainer);
            if (lastSetBit >= 0) {
                break;
            }
            pos--;
        }
        int cardinality = bitmap.getCardinality();
        assert cardinality == 0 || lastSetBit >= 0;
        return new CardinalityAndLastSetBit<>(bitmap, cardinality, lastSetBit);
    }

    private static int lastSetBit(short key, Container container) {
        int lastSetIndex = lastSetIndex(container);
        if (lastSetIndex == -1) {
            return -1;
        } else {
            int hs = Util.toIntUnsigned(key) << 16;
            return lastSetIndex | hs;
        }
    }

    private static int lastSetIndex(Container container) {
        if (container instanceof ArrayContainer) {
            ArrayContainer arrayContainer = (ArrayContainer) container;
            int cardinality = arrayContainer.cardinality;
            if (cardinality > 0) {
                short last = arrayContainer.content[cardinality - 1];
                return Util.toIntUnsigned(last);
            }
        } else if (container instanceof RunContainer) {
            RunContainer runContainer = (RunContainer) container;
            if (runContainer.nbrruns > 0) {
                int maxlength = Util.toIntUnsigned(runContainer.getLength(runContainer.nbrruns - 1));
                int base = Util.toIntUnsigned(runContainer.getValue(runContainer.nbrruns - 1));
                return (base + maxlength);
            }
        } else {
            // <-- trailing              leading -->
            // [ 0, 0, 0, 0, 0 ... , 0, 0, 0, 0, 0 ]
            BitmapContainer bitmapContainer = (BitmapContainer) container;
            long[] longs = bitmapContainer.bitmap;
            for (int i = longs.length - 1; i >= 0; i--) {
                long l = longs[i];
                int leadingZeros = Long.numberOfLeadingZeros(l);
                if (leadingZeros < 64) {
                    short last = (short) ((i * 64) + 64 - leadingZeros - 1);
                    return Util.toIntUnsigned(last);
                }
            }
        }
        return -1;
    }

    public static long sizeInBits(RoaringBitmap bitmap) {
        int pos = bitmap.highLowContainer.size() - 1;
        if (pos >= 0) {
            return (Util.toIntUnsigned(bitmap.highLowContainer.getKeyAtIndex(pos)) + 1) << 16;
        } else {
            return 0;
        }
    }

    public static void cardinalityInBuckets(RoaringBitmap bitmap, int[][] indexes, long[][] buckets) {
        // indexes = { 10, 20, 30, 40, 50 } length=5
        // buckets = { 10-19, 20-29, 30-39, 40-49 } length=4
        int numContainers = bitmap.highLowContainer.size();
        //System.out.println("NumContainers=" + numContainers);
        int bucketLength = buckets.length;
        int[] currentBucket = new int[bucketLength];
        Arrays.fill(currentBucket, 0);
        int[] currentBucketStart = new int[bucketLength];
        int[] currentBucketEnd = new int[bucketLength];
        for (int bi = 0; bi < bucketLength; bi++) {
            currentBucketStart[bi] = indexes[bi][currentBucket[bi]];
            currentBucketEnd[bi] = indexes[bi][currentBucket[bi] + 1];
        }

        int numExhausted = 0;
        boolean[] exhausted = new boolean[bucketLength];

        for (int pos = 0; pos < numContainers; pos++) {
            //System.out.println("pos=" + pos);
            int min = containerMin(bitmap, pos);
            for (int bi = 0; bi < bucketLength; bi++) {
                while (!exhausted[bi] && min >= currentBucketEnd[bi]) {
                    //System.out.println("Advance1 min:" + min + " >= currentBucketEnd:" + currentBucketEnd);
                    currentBucket[bi]++;
                    if (currentBucket[bi] == buckets[bi].length) {
                        numExhausted++;
                        exhausted[bi] = true;
                        break;
                    }
                    currentBucketStart[bi] = indexes[bi][currentBucket[bi]];
                    currentBucketEnd[bi] = indexes[bi][currentBucket[bi] + 1];
                }
            }
            if (numExhausted == bucketLength) {
                break;
            }

            boolean[] candidate = new boolean[bucketLength];
            boolean anyCandidates = false;
            for (int bi = 0; bi < bucketLength; bi++) {
                candidate[bi] = (min < currentBucketEnd[bi]);
                anyCandidates |= candidate[bi];
            }

            if (anyCandidates) {
                Container container = bitmap.highLowContainer.values[pos];
                int max = min + (1 << 16);
                boolean[] bucketContainsPos = new boolean[bucketLength];
                boolean allContainPos = true;
                boolean anyContainPos = false;
                for (int bi = 0; bi < bucketLength; bi++) {
                    bucketContainsPos[bi] = (currentBucketStart[bi] <= min && max <= currentBucketEnd[bi]);
                    allContainPos &= bucketContainsPos[bi];
                    anyContainPos |= bucketContainsPos[bi];
                }

                if (anyContainPos) {
                    int cardinality = container.getCardinality();
                    for (int bi = 0; bi < bucketLength; bi++) {
                        if (bucketContainsPos[bi]) {
                            //System.out.println("BucketContainsPos");
                            buckets[bi][currentBucket[bi]] += cardinality;
                        }
                    }
                }

                if (!allContainPos) {
                    if (container instanceof ArrayContainer) {
                        //System.out.println("ArrayContainer");
                        ArrayContainer arrayContainer = (ArrayContainer) container;
                        for (int i = 0; i < arrayContainer.cardinality && numExhausted < bucketLength; i++) {
                            int index = Util.toIntUnsigned(arrayContainer.content[i]) | min;
                            next:
                            for (int bi = 0; bi < bucketLength; bi++) {
                                if (!candidate[bi] || bucketContainsPos[bi] || exhausted[bi]) {
                                    continue;
                                }
                                while (index >= currentBucketEnd[bi]) {
                                    currentBucket[bi]++;
                                    if (currentBucket[bi] == buckets[bi].length) {
                                        numExhausted++;
                                        exhausted[bi] = true;
                                        continue next;
                                    }
                                    currentBucketStart[bi] = indexes[bi][currentBucket[bi]];
                                    currentBucketEnd[bi] = indexes[bi][currentBucket[bi] + 1];
                                }
                                if (index >= currentBucketStart[bi]) {
                                    buckets[bi][currentBucket[bi]]++;
                                }
                            }
                        }
                    } else if (container instanceof RunContainer) {
                        RunContainer runContainer = (RunContainer) container;
                        for (int i = 0; i < runContainer.nbrruns && numExhausted < bucketLength; i++) {
                            int base = Util.toIntUnsigned(runContainer.getValue(i));

                            int startInclusive = base | min;
                            int endExclusive = startInclusive + 1 + Util.toIntUnsigned(runContainer.getLength(i));
                            for (int index = startInclusive; index < endExclusive && numExhausted < bucketLength; index++) {
                                next:
                                for (int bi = 0; bi < bucketLength; bi++) {
                                    if (!candidate[bi] || bucketContainsPos[bi] || exhausted[bi]) {
                                        continue;
                                    }
                                    while (index >= currentBucketEnd[bi]) {
                                        currentBucket[bi]++;
                                        if (currentBucket[bi] == buckets[bi].length) {
                                            numExhausted++;
                                            exhausted[bi] = true;
                                            continue next;
                                        }
                                        currentBucketStart[bi] = indexes[bi][currentBucket[bi]];
                                        currentBucketEnd[bi] = indexes[bi][currentBucket[bi] + 1];
                                    }
                                    if (index >= currentBucketStart[bi]) {
                                        buckets[bi][currentBucket[bi]]++;
                                    }
                                }
                            }
                        }
                    } else {
                        //System.out.println("BitmapContainer");
                        BitmapContainer bitmapContainer = (BitmapContainer) container;
                        // nextSetBit no longer performs a bounds check
                        int maxIndex = bitmapContainer.bitmap.length << 6;
                        for (int i = bitmapContainer.nextSetBit(0);
                             i >= 0 && numExhausted < bucketLength;
                             i = (i + 1 >= maxIndex) ? -1 : bitmapContainer.nextSetBit(i + 1)) {
                            int index = Util.toIntUnsigned((short) i) | min;
                            next:
                            for (int bi = 0; bi < bucketLength; bi++) {
                                if (!candidate[bi] || bucketContainsPos[bi] || exhausted[bi]) {
                                    continue;
                                }
                                while (index >= currentBucketEnd[bi]) {
                                    currentBucket[bi]++;
                                    if (currentBucket[bi] == buckets[bi].length) {
                                        numExhausted++;
                                        exhausted[bi] = true;
                                        continue next;
                                    }
                                    currentBucketStart[bi] = indexes[bi][currentBucket[bi]];
                                    currentBucketEnd[bi] = indexes[bi][currentBucket[bi] + 1];
                                }
                                if (index >= currentBucketStart[bi]) {
                                    buckets[bi][currentBucket[bi]]++;
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static int containerMin(RoaringBitmap bitmap, int pos) {
        return Util.toIntUnsigned(bitmap.highLowContainer.getKeyAtIndex(pos)) << 16;
    }

    public static int key(int position) {
        return Util.highbits(position);
    }

    public static int[] keys(RoaringBitmap bitmap) {
        short[] bitmapKeys = bitmap.highLowContainer.keys;
        int[] copyKeys = new int[bitmap.highLowContainer.size];
        for (int i = 0; i < copyKeys.length; i++) {
            copyKeys[i] = Util.toIntUnsigned(bitmapKeys[i]);
        }
        return copyKeys;
    }

    public static int[] userialize(RoaringBitmap bitmap, DataOutput[] outContainers) throws IOException {
        return shortToIntKeys(serialize(bitmap, outContainers));
    }

    public static boolean[] userializeAtomized(RoaringBitmap index, int[] ukeys, DataOutput[] dataOutputs) throws IOException {
        RoaringArray array = index.highLowContainer;
        short[] keys = intToShortKeys(ukeys);
        boolean[] out = new boolean[keys.length];
        for (int i = 0; i < keys.length; i++) {
            if (dataOutputs[i] == null) {
                continue;
            }
            Container container = array.getContainer(keys[i]);
            if (container != null) {
                serializeAtomized(container, dataOutputs[i]);
                out[i] = true;
            } else {
                out[i] = false;
            }
        }
        return out;
    }

    public static short[] serialize(RoaringBitmap bitmap, DataOutput[] outContainers) throws IOException {
        RoaringArray array = bitmap.highLowContainer;

        short[] keys = new short[outContainers.length];
        for (int k = 0; k < array.size; ++k) {
            keys[k] = array.keys[k];
            serializeAtomized(array.values[k], outContainers[k]);
        }
        return keys;
    }

    private static void serializeAtomized(Container value, DataOutput outContainer) throws IOException {
        outContainer.writeShort(lastSetIndex(value));
        outContainer.writeBoolean(value instanceof RunContainer);
        outContainer.writeShort(Short.reverseBytes((short) (value.getCardinality() - 1)));

        value.writeArray(outContainer);
    }

    public static long[] serializeSizeInBytes(RoaringBitmap bitmap, int[] ukeys) {
        RoaringArray array = bitmap.highLowContainer;
        short[] keys = intToShortKeys(ukeys);
        long[] sizes = new long[keys.length];
        for (int i = 0; i < keys.length; i++) {
            Container container = array.getContainer(keys[i]);
            sizes[i] = (container == null) ? -1 : container.serializedSizeInBytes();
        }
        return sizes;
    }

    public static boolean udeserialize(BitmapAndLastId<RoaringBitmap> result, StreamAtoms streamAtoms) throws IOException {
        try {
            List<ContainerAndLastSetBit> containers = Lists.newArrayList();
            boolean v = streamAtoms.stream((key, dataInput) -> {
                containers.add(deserializeContainer(intToShortKey(key), dataInput));
                return true;
            });
            if (containers.isEmpty()) {
                result.clear();
            } else {
                boolean isAscending = true;
                boolean isDescending = true;
                for (int i = 1; i < containers.size(); i++) {
                    short lastKey = containers.get(i - 1).key;
                    short nextKey = containers.get(i).key;
                    if (lastKey > nextKey) {
                        isAscending = false;
                    } else if (lastKey < nextKey) {
                        isDescending = false;
                    }
                }
                if (isAscending) {
                    // do nothing
                } else if (isDescending) {
                    Collections.reverse(containers);
                } else {
                    Collections.sort(containers);
                }
                RoaringBitmap bitmap = new RoaringBitmap();
                int lastSetBit = deserialize(bitmap, containers);
                result.set(bitmap, lastSetBit);
            }
            return v;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    public static int lastSetBit(int ukey, DataInput inContainer) throws IOException {
        int last = inContainer.readShort() & 0xFFFF;
        int hs = ukey << 16;
        return last | hs;
    }

    @VisibleForTesting
    static int deserialize(RoaringBitmap bitmap, List<ContainerAndLastSetBit> containers) throws IOException {
        RoaringArray array = bitmap.highLowContainer;

        array.clear();
        array.size = containers.size();

        if ((array.keys == null) || (array.keys.length < array.size)) {
            array.keys = new short[array.size];
            array.values = new Container[array.size];
        }

        int lastSetBit = -1;
        //Reading the containers
        for (int k = 0; k < containers.size(); ++k) {
            ContainerAndLastSetBit container = containers.get(k);
            array.keys[k] = container.key;
            array.values[k] = container.container;
            lastSetBit = Math.max(lastSetBit, container.lastSetBit);
        }
        return lastSetBit;
    }

    private static ContainerAndLastSetBit deserializeContainer(short key, DataInput inContainer) throws IOException {

        int last = inContainer.readShort() & 0xFFFF;
        boolean isRun = inContainer.readBoolean();
        int cardinality = 1 + (0xFFFF & Short.reverseBytes(inContainer.readShort()));
        boolean isBitmap = cardinality > ArrayContainer.DEFAULT_MAX_SIZE;

        int hs = Util.toIntUnsigned(key) << 16;
        int lastSetBit = last | hs;

        Container val;
        if (!isRun && isBitmap) {
            final long[] bitmapArray = new long[BitmapContainer.MAX_CAPACITY / 64];
            // little endian
            for (int l = 0; l < bitmapArray.length; ++l) {
                bitmapArray[l] = Long.reverseBytes(inContainer.readLong());
            }
            val = new BitmapContainer(bitmapArray, cardinality);
        } else if (isRun) {
            // cf RunContainer.writeArray()
            int nbrruns = Util.toIntUnsigned(Short.reverseBytes(inContainer.readShort()));
            final short lengthsAndValues[] = new short[2 * nbrruns];

            for (int j = 0; j < 2 * nbrruns; ++j) {
                lengthsAndValues[j] = Short.reverseBytes(inContainer.readShort());
            }
            val = new RunContainer(lengthsAndValues, nbrruns);
        } else {
            final short[] shortArray = new short[cardinality];
            for (int l = 0; l < shortArray.length; ++l) {
                shortArray[l] = Short.reverseBytes(inContainer.readShort());
            }
            val = new ArrayContainer(shortArray);
        }
        return new ContainerAndLastSetBit(key, val, lastSetBit);
    }

    private static class ContainerAndLastSetBit implements Comparable<ContainerAndLastSetBit> {
        private final short key;
        private final Container container;
        private final int lastSetBit;

        private ContainerAndLastSetBit(short key, Container container, int lastSetBit) {
            this.key = key;
            this.container = container;
            this.lastSetBit = lastSetBit;
        }

        @Override
        public int compareTo(ContainerAndLastSetBit o) {
            return Shorts.compare(key, o.key);
        }
    }

    public static short intToShortKey(int ukey) {
        return Util.lowbits(ukey);
    }

    public static short[] intToShortKeys(int[] ukeys) {
        short[] keys = new short[ukeys.length];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = Util.lowbits(ukeys[i]);
        }
        return keys;
    }

    public static int shortToIntKey(short key) {
        return Util.toIntUnsigned(key);
    }

    public static int[] shortToIntKeys(short[] keys) {
        int[] ukeys = new int[keys.length];
        for (int i = 0; i < ukeys.length; i++) {
            ukeys[i] = Util.toIntUnsigned(keys[i]);
        }
        return ukeys;
    }

    public static int containerCount(RoaringBitmap bitmap) {
        return bitmap.highLowContainer.size;
    }

    private RoaringInspection() {
    }
}