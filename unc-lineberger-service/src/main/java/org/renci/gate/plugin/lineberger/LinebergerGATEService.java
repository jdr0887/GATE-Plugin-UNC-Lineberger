package org.renci.gate.plugin.lineberger;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
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
import org.renci.jlrm.sge.ssh.SGESSHJob;
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

    private final List<SGESSHJob> jobCache = new ArrayList<SGESSHJob>();

    public LinebergerGATEService() {
        super();
    }

    @Override
    public Map<String, GlideinMetric> lookupMetrics() throws GATEException {
        Map<String, GlideinMetric> metricsMap = new HashMap<String, GlideinMetric>();

        try {
            SGESSHLookupStatusCallable callable = new SGESSHLookupStatusCallable(getSite(), jobCache);
            Set<SGEJobStatusInfo> jobStatusSet = Executors.newSingleThreadExecutor().submit(callable).get();

            // get unique list of queues
            Set<String> queueSet = new HashSet<String>();
            if (jobStatusSet != null) {
                for (SGEJobStatusInfo info : jobStatusSet) {
                    queueSet.add(info.getQueue());
                }
            }

            Iterator<SGESSHJob> jobCacheIter = jobCache.iterator();
            while (jobCacheIter.hasNext()) {
                SGESSHJob job = jobCacheIter.next();
                for (String queue : queueSet) {
                    int running = 0;
                    int pending = 0;
                    for (SGEJobStatusInfo info : jobStatusSet) {
                        GlideinMetric metrics = new GlideinMetric();
                        if (info.getQueue().equals(queue) && job.getId().equals(info.getJobId())) {
                            switch (info.getType()) {
                                case WAITING:
                                    ++pending;
                                    break;
                                case RUNNING:
                                    ++running;
                                    break;
                                case DELETION:
                                case ERROR:
                                case DONE:
                                case THRESHOLD:
                                    jobCacheIter.remove();
                                    break;
                                case SUSPENDED:
                                case HOLD:
                                case RESTARTED:
                                case TRANSFERING:
                                default:
                                    break;
                            }
                        }
                        metrics.setQueue(queue);
                        metrics.setPending(pending);
                        metrics.setRunning(running);
                        metricsMap.put(queue, metrics);
                    }
                }
            }
        } catch (Exception e ) {
            throw new GATEException(e);
        }

        return metricsMap;
    }

    @Override
    public void createGlidein(Queue queue) throws GATEException {
        logger.info("ENTERING createGlidein(Queue)");

        if (StringUtils.isNotEmpty(getActiveQueues()) && !getActiveQueues().contains(queue.getName())) {
            logger.warn("queue name is not in active queue list...see etc/org.renci.gate.plugin.kure.cfg");
            return;
        }

        File submitDir = new File("/tmp", System.getProperty("user.name"));
        submitDir.mkdirs();
        SGESSHJob job = null;

        String hostAllow = "*.unc.edu";
        SGESSHSubmitCondorGlideinCallable callable = new SGESSHSubmitCondorGlideinCallable(getSite(), queue, submitDir,
                "glidein", getCollectorHost(), hostAllow, hostAllow, 40);
        try {
            job = Executors.newSingleThreadExecutor().submit(callable).get();
            if (job != null && StringUtils.isNotEmpty(job.getId())) {
                logger.info("job.getId(): {}", job.getId());
                jobCache.add(job);
            }
        } catch (Exception e ) {
            throw new GATEException(e);
        }
    }

    @Override
    public void deleteGlidein(Queue queue) throws GATEException {
        if (jobCache.size() > 0) {
            try {
                SGESSHJob job = jobCache.get(0);
                SGESSHKillCallable callable = new SGESSHKillCallable(getSite(), job);
                Executors.newSingleThreadExecutor().submit(callable).get();
                jobCache.remove(0);
            } catch (Exception e ) {
                throw new GATEException(e);
            }
        }
    }

    @Override
    public void deletePendingGlideins() throws GATEException {
        try {
            SGESSHLookupStatusCallable lookupStatusCallable = new SGESSHLookupStatusCallable(getSite(), jobCache);
            Set<SGEJobStatusInfo> jobStatusSet = Executors.newSingleThreadExecutor().submit(lookupStatusCallable).get();
            for (SGEJobStatusInfo info : jobStatusSet) {
                switch (info.getType()) {
                    case WAITING:
                        deleteGlidein(getSite().getQueueInfoMap().get(info.getQueue()));
                        break;
                }
            }
        } catch (Exception e ) {
            throw new GATEException(e);
        }
    }

}
