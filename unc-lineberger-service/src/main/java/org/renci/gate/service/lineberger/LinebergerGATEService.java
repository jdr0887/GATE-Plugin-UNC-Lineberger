package org.renci.gate.service.lineberger;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import org.renci.gate.AbstractGATEService;
import org.renci.gate.GATEException;
import org.renci.gate.GlideinMetric;
import org.renci.jlrm.Queue;
import org.renci.jlrm.sge.SGEJobStatusInfo;
import org.renci.jlrm.sge.SGEJobStatusType;
import org.renci.jlrm.sge.ssh.SGESSHKillCallable;
import org.renci.jlrm.sge.ssh.SGESSHLookupStatusCallable;
import org.renci.jlrm.sge.ssh.SGESSHSubmitCondorGlideinCallable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 
 * @author jdr0887
 */
public class LinebergerGATEService extends AbstractGATEService {

    private final Logger logger = LoggerFactory.getLogger(LinebergerGATEService.class);

    public LinebergerGATEService() {
        super();
    }

    @Override
    public Boolean isValid() throws GATEException {
        logger.debug("ENTERING isValid()");
        return true;
    }

    @Override
    public List<GlideinMetric> lookupMetrics() throws GATEException {
        logger.debug("ENTERING lookupMetrics()");
        Map<String, GlideinMetric> metricsMap = new HashMap<String, GlideinMetric>();

        List<Queue> queueList = getSite().getQueueList();
        for (Queue queue : queueList) {
            metricsMap.put(queue.getName(), new GlideinMetric(getSite().getName(), queue.getName(), 0, 0));
        }

        try {
            SGESSHLookupStatusCallable callable = new SGESSHLookupStatusCallable(getSite());
            Set<SGEJobStatusInfo> jobStatusSet = Executors.newSingleThreadExecutor().submit(callable).get();
            logger.debug("jobStatusSet.size(): {}", jobStatusSet.size());

            if (jobStatusSet != null && jobStatusSet.size() > 0) {

                for (SGEJobStatusInfo info : jobStatusSet) {

                    if (!"glidein".equals(info.getJobName())) {
                        continue;
                    }

                    switch (info.getType()) {
                        case WAITING:
                            metricsMap.get(info.getQueue()).incrementPending();
                            break;
                        case RUNNING:
                            metricsMap.get(info.getQueue()).incrementRunning();
                            break;
                    }
                }

            }

        } catch (Exception e) {
            throw new GATEException(e);
        }

        List<GlideinMetric> metricList = new ArrayList<GlideinMetric>();
        metricList.addAll(metricsMap.values());

        return metricList;
    }

    @Override
    public void createGlidein(Queue queue) throws GATEException {
        logger.debug("ENTERING createGlidein(Queue)");

        File submitDir = new File("/tmp", System.getProperty("user.name"));
        submitDir.mkdirs();

        try {
            String hostAllow = "*.unc.edu";
            SGESSHSubmitCondorGlideinCallable callable = new SGESSHSubmitCondorGlideinCallable();
            callable.setCollectorHost(getCollectorHost());
            callable.setUsername(System.getProperty("user.name"));
            callable.setSite(getSite());
            callable.setJobName("glidein");
            callable.setQueue(queue);
            callable.setSubmitDir(submitDir);
            callable.setRequiredMemory(40);
            callable.setHostAllowRead(hostAllow);
            callable.setHostAllowWrite(hostAllow);
            Executors.newSingleThreadExecutor().submit(callable).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new GATEException(e);
        }

    }

    @Override
    public void deleteGlidein(Queue queue) throws GATEException {
        try {
            SGESSHLookupStatusCallable lookupStatusCallable = new SGESSHLookupStatusCallable(getSite());
            Set<SGEJobStatusInfo> jobStatusSet = Executors.newSingleThreadExecutor().submit(lookupStatusCallable).get();

            Iterator<SGEJobStatusInfo> iter = jobStatusSet.iterator();
            while (iter.hasNext()) {
                SGEJobStatusInfo info = iter.next();
                if (!info.getJobName().equals("glidein")) {
                    continue;
                }
                logger.debug("deleting: {}", info.toString());
                SGESSHKillCallable killCallable = new SGESSHKillCallable(getSite(), info.getJobId());
                Executors.newSingleThreadExecutor().submit(killCallable).get();
                // only delete one...engine will trigger next deletion
                break;
            }
        } catch (Exception e) {
            throw new GATEException(e);
        }
    }

    @Override
    public void deletePendingGlideins() throws GATEException {
        try {
            SGESSHLookupStatusCallable lookupStatusCallable = new SGESSHLookupStatusCallable(getSite());
            Set<SGEJobStatusInfo> jobStatusSet = Executors.newSingleThreadExecutor().submit(lookupStatusCallable).get();
            for (SGEJobStatusInfo info : jobStatusSet) {
                if (!info.getJobName().equals("glidein")) {
                    continue;
                }
                if (info.getType().equals(SGEJobStatusType.WAITING)) {
                    logger.debug("deleting: {}", info.toString());
                    SGESSHKillCallable callable = new SGESSHKillCallable(getSite(), info.getJobId());
                    Executors.newSingleThreadExecutor().submit(callable).get();
                    // throttle the deleteGlidein calls such that SSH doesn't complain
                    Thread.sleep(2000);
                }
            }
        } catch (Exception e) {
            throw new GATEException(e);
        }
    }

}
