package com.jivesoftware.os.miru.service.index.filer;

import com.jivesoftware.os.filer.io.StripingLocksProvider;
import com.jivesoftware.os.filer.io.api.KeyedFilerStore;
import com.jivesoftware.os.filer.io.api.StackBuffer;
import com.jivesoftware.os.miru.api.base.MiruStreamId;
import com.jivesoftware.os.miru.plugin.bitmap.MiruBitmaps;
import com.jivesoftware.os.miru.plugin.index.MiruInboxIndex;
import com.jivesoftware.os.miru.plugin.index.MiruInvertedIndex;
import com.jivesoftware.os.miru.plugin.index.MiruInvertedIndexAppender;

/** @author jonathan */
public class MiruFilerInboxIndex<BM extends IBM, IBM> implements MiruInboxIndex<IBM> {

    private final MiruBitmaps<BM, IBM> bitmaps;
    private final KeyedFilerStore<Long, Void> store;
    private final StripingLocksProvider<MiruStreamId> stripingLocksProvider;

    public MiruFilerInboxIndex(MiruBitmaps<BM, IBM> bitmaps,
        KeyedFilerStore<Long, Void> store,
        StripingLocksProvider<MiruStreamId> stripingLocksProvider)
        throws Exception {
        this.bitmaps = bitmaps;
        this.store = store;
        this.stripingLocksProvider = stripingLocksProvider;
    }

    @Override
    public void append(MiruStreamId streamId, StackBuffer stackBuffer, int... ids) throws Exception {
        getAppender(streamId).append(stackBuffer, ids);
    }

    @Override
    public MiruInvertedIndex<IBM> getInbox(MiruStreamId streamId) {
        return new MiruFilerInvertedIndex<>(bitmaps,
            streamId.getBytes(),
            store,
            -1,
            stripingLocksProvider.lock(streamId, 0));
    }

    @Override
    public MiruInvertedIndexAppender getAppender(MiruStreamId streamId) throws Exception {
        return getInbox(streamId);
    }

    @Override
    public int getLastActivityIndex(MiruStreamId streamId, StackBuffer stackBuffer) throws Exception {
        return getInbox(streamId).lastId(stackBuffer);
    }

    @Override
    public void close() {
        store.close();
    }
}
