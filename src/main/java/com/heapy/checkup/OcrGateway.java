package com.heapy.checkup;

import com.heapy.checkup.OcrModels.Job;
import com.heapy.checkup.OcrModels.Snapshot;

public interface OcrGateway {
    void prepare(Job job, byte[] source);
    Snapshot read(Job job);
    Snapshot execute(Job job);
    void purge(Job job, String reason);
    void purgeForWithdrawal(Job job);
}
