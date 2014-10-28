package com.jivesoftware.os.miru.service.index.disk;

import com.google.common.base.Optional;
import com.jivesoftware.os.filer.io.KeyPartitioner;
import com.jivesoftware.os.filer.io.KeyValueMarshaller;
import com.jivesoftware.os.filer.map.store.FileBackedMapChunkFactory;
import com.jivesoftware.os.filer.map.store.VariableKeySizeMapChunkBackedMapStore;
import com.jivesoftware.os.filer.map.store.api.KeyValueStoreException;
import com.jivesoftware.os.miru.api.MiruPartitionState;
import com.jivesoftware.os.miru.api.activity.schema.MiruFieldDefinition;
import com.jivesoftware.os.miru.api.base.MiruTenantId;
import com.jivesoftware.os.miru.api.base.MiruTermId;
import com.jivesoftware.os.miru.plugin.index.MiruField;
import com.jivesoftware.os.miru.plugin.index.MiruInvertedIndex;
import com.jivesoftware.os.miru.service.index.BulkEntry;
import com.jivesoftware.os.miru.service.index.BulkExport;
import com.jivesoftware.os.miru.service.index.BulkImport;
import com.jivesoftware.os.miru.service.index.MiruFieldIndexKey;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Iterator;

/**
 * Persistent impl. Term dictionary is mem-mapped. Does not support index(), so no next term id.
 */
public class MiruOnDiskField<BM> implements MiruField<BM>, BulkImport<Iterator<BulkEntry<MiruTermId, MiruFieldIndexKey>>> {

    private static final int[] KEY_SIZE_THRESHOLDS = new int[] { 4, 16, 64, 256, 1_024 }; //TODO make this configurable per field?
    private static final int PAYLOAD_SIZE = 8; // 2 ints (MiruFieldIndexKey)

    private final MiruFieldDefinition fieldDefinition;
    private final MiruOnDiskIndex<BM> index;
    private final VariableKeySizeMapChunkBackedMapStore<MiruTermId, MiruFieldIndexKey> termToIndex;

    public MiruOnDiskField(MiruFieldDefinition fieldDefinition, MiruOnDiskIndex<BM> index, String[] baseMapDirectories) throws Exception {
        this.fieldDefinition = fieldDefinition;
        this.index = index;

        VariableKeySizeMapChunkBackedMapStore.Builder<MiruTermId, MiruFieldIndexKey> mapStoreBuilder = new VariableKeySizeMapChunkBackedMapStore
            .Builder<>(64, null, new KeyValueMarshaller<MiruTermId, MiruFieldIndexKey>() {

            @Override
            public byte[] keyBytes(MiruTermId key) {
                return key.getBytes();
            }

            @Override
            public byte[] valueBytes(MiruFieldIndexKey value) {
                ByteBuffer buf = ByteBuffer.allocate(8); // 2 ints
                buf.putInt(value.getId());
                buf.putInt(value.getMaxId());
                return buf.array();
            }

            @Override
            public MiruTermId bytesKey(byte[] bytes, int offset) {
                if (offset != 0) {
                    throw new UnsupportedOperationException("offset not supported");
                }
                return new MiruTermId(bytes);
            }

            @Override
            public MiruFieldIndexKey bytesValue(MiruTermId key, byte[] bytes, int offset) {
                ByteBuffer buf = ByteBuffer.wrap(bytes, offset, 8); // 2 ints
                return new MiruFieldIndexKey(buf.getInt(), buf.getInt());
            }
        });

        for (int keySize : KEY_SIZE_THRESHOLDS) {
            String[] mapDirectories = new String[baseMapDirectories.length];
            for (int i = 0; i < mapDirectories.length; i++) {
                mapDirectories[i] = new File(baseMapDirectories[i], String.valueOf(keySize)).getAbsolutePath();
            }
            mapStoreBuilder.add(keySize, new FileBackedMapChunkFactory(keySize, true, PAYLOAD_SIZE, false, 100, mapDirectories),
                new KeyPartitioner<MiruTermId>() {
                    @Override
                    public String keyPartition(MiruTermId miruTermId) {
                        return "0";
                    }

                    @Override
                    public Iterable<String> allPartitions() {
                        return Collections.singletonList("0");
                    }
                });
        }

        this.termToIndex = mapStoreBuilder.build();
    }

    @Override
    public MiruFieldDefinition getFieldDefinition() {
        return fieldDefinition;
    }

    @Override
    public void notifyStateChange(MiruPartitionState state) {
        // do nothing
    }

    @Override
    public long sizeInMemory() throws Exception {
        return 0;
    }

    @Override
    public long sizeOnDisk() throws Exception {
        return termToIndex.estimateSizeInBytes();
    }

    @Override
    public void index(MiruTermId term, int... ids) throws Exception {
        throw new UnsupportedOperationException("On disk index is read only");
    }

    @Override
    public void remove(MiruTermId term, int id) throws Exception {
        Optional<MiruInvertedIndex<BM>> index = getInvertedIndex(term);
        if (index.isPresent()) {
            index.get().remove(id);
        }
    }

    @Override
    public MiruInvertedIndex<BM> getOrCreateInvertedIndex(MiruTermId term) throws Exception {
        throw new UnsupportedOperationException("On disk index is read only");
    }

    @Override
    public Optional<MiruInvertedIndex<BM>> getInvertedIndex(MiruTermId term, int considerIfIndexIdGreaterThanN) throws Exception {
        Optional<MiruFieldIndexKey> indexKey = getTermId(term);
        if (indexKey.isPresent() && indexKey.get().getMaxId() > considerIfIndexIdGreaterThanN) {
            return getInvertedIndex(term);
        }
        return Optional.absent();
    }

    @Override
    public Optional<MiruInvertedIndex<BM>> getInvertedIndex(MiruTermId term) throws Exception {
        Optional<MiruFieldIndexKey> indexKey = getTermId(term);
        if (indexKey.isPresent()) {
            return getInvertedIndex(indexKey.get());
        }
        return Optional.absent();
    }

    public MiruOnDiskIndex<BM> getIndex() {
        return index;
    }

    private Optional<MiruInvertedIndex<BM>> getInvertedIndex(MiruFieldIndexKey indexKey) throws Exception {
        if (indexKey != null) {
            return index.get(fieldDefinition.fieldId, indexKey.getId());
        }
        return Optional.absent();
    }

    private Optional<MiruFieldIndexKey> getTermId(MiruTermId term) throws KeyValueStoreException {
        MiruFieldIndexKey indexKey = termToIndex.get(term);
        return Optional.fromNullable(indexKey);
    }

    @Override
    public void bulkImport(MiruTenantId tenantId, BulkExport<Iterator<BulkEntry<MiruTermId, MiruFieldIndexKey>>> importItems) throws Exception {
        Iterator<BulkEntry<MiruTermId, MiruFieldIndexKey>> iter = importItems.bulkExport(tenantId);
        while (iter.hasNext()) {
            BulkEntry<MiruTermId, MiruFieldIndexKey> entry = iter.next();
            termToIndex.add(entry.key, entry.value);
        }
    }
}
