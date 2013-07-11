package org.renci.gate.plugin.lineberger;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import org.apache.commons.lang.StringUtils;
import org.renci.gate.AbstractGATEService;
import org.renci.gate.GATEException;
import org.renci.gate.GlideinMetric;
import org.renci.jlrm.Queue;
import org.renci.jlrm.sge.SGEJobStatusInfo;
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
        logger.info("ENTERING isValid()");
        return true;
    }

    @Override
    public Map<String, GlideinMetric> lookupMetrics() throws GATEException {
        logger.info("ENTERING lookupMetrics()");
        Map<String, GlideinMetric> metricsMap = new HashMap<String, GlideinMetric>();

        try {
            SGESSHLookupStatusCallable callable = new SGESSHLookupStatusCallable(getSite());
            Set<SGEJobStatusInfo> jobStatusSet = Executors.newSingleThreadExecutor().submit(callable).get();
            logger.debug("jobStatusSet.size(): {}", jobStatusSet.size());

            // get unique list of queues
            Set<String> queueSet = new HashSet<String>();
            if (jobStatusSet != null && jobStatusSet.size() > 0) {
                for (SGEJobStatusInfo info : jobStatusSet) {
                    if (!queueSet.contains(info.getQueue())) {
                        queueSet.add(info.getQueue());
                    }
                }

                for (SGEJobStatusInfo info : jobStatusSet) {
                    if (metricsMap.containsKey(info.getQueue())) {
                        continue;
                    }
                    if (!"glidein".equals(info.getJobName())) {
                        continue;
                    }
                    metricsMap.put(info.getQueue(), new GlideinMetric(0, 0, info.getQueue()));
                }

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

        return metricsMap;

    }

    @Override
    public void createGlidein(Queue queue) throws GATEException {
        logger.info("ENTERING createGlidein(Queue)");

        if (StringUtils.isNotEmpty(getActiveQueues()) && !getActiveQueues().contains(queue.getName())) {
            logger.warn("queue name is not in active queue list...see etc/org.renci.gate.plugin.lineberger.cfg");
            return;
        }

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
            SGESSHKillCallable callable = new SGESSHKillCallable(getSite(), jobStatusSet.iterator().next().getJobId());
            Executors.newSingleThreadExecutor().submit(callable).get();
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
                switch (info.getType()) {
                    case WAITING:
                        deleteGlidein(getSite().getQueueInfoMap().get(info.getQueue()));
                        break;
                }
            }
        } catch (Exception e) {
            throw new GATEException(e);
        }
    }

}
