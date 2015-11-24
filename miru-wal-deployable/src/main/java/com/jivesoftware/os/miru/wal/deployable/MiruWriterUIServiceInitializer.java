package com.jivesoftware.os.miru.wal.deployable;

import com.jivesoftware.os.miru.api.MiruStats;
import com.jivesoftware.os.miru.api.wal.AmzaCursor;
import com.jivesoftware.os.miru.api.wal.AmzaSipCursor;
import com.jivesoftware.os.miru.api.wal.RCVSCursor;
import com.jivesoftware.os.miru.api.wal.RCVSSipCursor;
import com.jivesoftware.os.miru.ui.MiruSoyRenderer;
import com.jivesoftware.os.miru.wal.MiruWALDirector;
import com.jivesoftware.os.miru.wal.activity.MiruActivityWALReader;
import com.jivesoftware.os.miru.wal.deployable.region.MiruAdminRegion;
import com.jivesoftware.os.miru.wal.deployable.region.MiruCleanupRegion;
import com.jivesoftware.os.miru.wal.deployable.region.MiruHeaderRegion;
import com.jivesoftware.os.miru.wal.deployable.region.MiruReadWALRegion;
import com.jivesoftware.os.miru.wal.deployable.region.MiruRepairRegion;
import com.jivesoftware.os.miru.wal.deployable.region.RCVSActivityWALRegion;

public class MiruWriterUIServiceInitializer {

    public MiruWALUIService initialize(MiruSoyRenderer renderer,
        MiruWALDirector<RCVSCursor, RCVSSipCursor> rcvsWALDirector,
        MiruWALDirector<AmzaCursor, AmzaSipCursor> amzaWALDirector,
        MiruActivityWALReader activityWALReader,
        MiruStats miruStats)
        throws Exception {

        return new MiruWALUIService(
            renderer,
            new MiruHeaderRegion("soy.miru.chrome.headerRegion", renderer),
            new MiruAdminRegion("soy.miru.page.adminRegion", renderer, miruStats),
            new RCVSActivityWALRegion("soy.miru.page.activityWalRegion", renderer, rcvsWALDirector),
            new MiruReadWALRegion("soy.miru.page.readWalRegion", renderer, rcvsWALDirector),
            new MiruRepairRegion("soy.miru.page.repairRegion", renderer, activityWALReader, rcvsWALDirector),
            new MiruCleanupRegion("soy.miru.page.cleanupRegion", renderer, rcvsWALDirector));
    }
}
