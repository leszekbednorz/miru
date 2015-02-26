package com.jivesoftware.os.miru.service.writer;

import com.jivesoftware.os.miru.api.MiruWriter;
import com.jivesoftware.os.miru.api.activity.MiruPartitionedActivity;
import com.jivesoftware.os.miru.service.MiruService;
import java.util.List;

/**
 *
 * @author jonathan
 */
public class MiruWriterImpl implements MiruWriter {

    private final MiruService miruService;

    public MiruWriterImpl(MiruService miruService) {
        this.miruService = miruService;
    }

    @Override
    public void writeToIndex(List<MiruPartitionedActivity> activities) throws Exception {
        miruService.writeToIndex(activities);
    }

}
