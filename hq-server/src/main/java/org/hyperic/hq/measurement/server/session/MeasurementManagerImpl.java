/*
 * NOTE: This copyright does *not* cover user programs that use HQ
 * program services by normal system calls through the application
 * program interfaces provided as part of the Hyperic Plug-in Development
 * Kit or the Hyperic Client Development Kit - this is merely considered
 * normal use of the program, and does *not* fall under the heading of
 * "derived work".
 * 
 * Copyright (C) [2004-2012], VMWare, Inc.
 * This file is part of HQ.
 * 
 * HQ is free software; you can redistribute it and/or modify
 * it under the terms version 2 of the GNU General Public License as
 * published by the Free Software Foundation. This program is distributed
 * in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307
 * USA.
 */

package org.hyperic.hq.measurement.server.session;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.PreDestroy;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.ObjectNotFoundException;
import org.hibernate.UnresolvableObjectException;
import org.hyperic.hq.agent.server.session.AgentDataTransferJob;
import org.hyperic.hq.agent.server.session.AgentSynchronizer;
import org.hyperic.hq.appdef.Agent;
import org.hyperic.hq.appdef.AppService;
import org.hyperic.hq.appdef.server.session.AppdefResource;
import org.hyperic.hq.appdef.server.session.Application;
import org.hyperic.hq.appdef.server.session.ApplicationDAO;
import org.hyperic.hq.appdef.server.session.Platform;
import org.hyperic.hq.appdef.server.session.ResourceCreatedZevent;
import org.hyperic.hq.appdef.server.session.ResourceRefreshZevent;
import org.hyperic.hq.appdef.server.session.ResourceUpdatedZevent;
import org.hyperic.hq.appdef.server.session.ResourceZevent;
import org.hyperic.hq.appdef.server.session.Server;
import org.hyperic.hq.appdef.server.session.Service;
import org.hyperic.hq.appdef.shared.AgentManager;
import org.hyperic.hq.appdef.shared.AgentNotFoundException;
import org.hyperic.hq.appdef.shared.AppdefEntityID;
import org.hyperic.hq.appdef.shared.AppdefEntityNotFoundException;
import org.hyperic.hq.appdef.shared.AppdefEntityValue;
import org.hyperic.hq.appdef.shared.AppdefResourceValue;
import org.hyperic.hq.appdef.shared.AppdefUtil;
import org.hyperic.hq.appdef.shared.ApplicationNotFoundException;
import org.hyperic.hq.appdef.shared.ConfigFetchException;
import org.hyperic.hq.appdef.shared.ConfigManager;
import org.hyperic.hq.appdef.shared.InvalidConfigException;
import org.hyperic.hq.authz.server.session.AuthzSubject;
import org.hyperic.hq.authz.server.session.Resource;
import org.hyperic.hq.authz.server.session.ResourceDeleteRequestedEvent;
import org.hyperic.hq.authz.server.session.ResourceGroup;
import org.hyperic.hq.authz.server.session.ResourceType;
import org.hyperic.hq.authz.shared.AuthzConstants;
import org.hyperic.hq.authz.shared.AuthzSubjectManager;
import org.hyperic.hq.authz.shared.PermissionException;
import org.hyperic.hq.authz.shared.PermissionManager;
import org.hyperic.hq.authz.shared.ResourceGroupManager;
import org.hyperic.hq.authz.shared.ResourceManager;
import org.hyperic.hq.events.MaintenanceEvent;
import org.hyperic.hq.measurement.MeasurementConstants;
import org.hyperic.hq.measurement.MeasurementCreateException;
import org.hyperic.hq.measurement.MeasurementNotFoundException;
import org.hyperic.hq.measurement.TemplateNotFoundException;
import org.hyperic.hq.measurement.agent.client.AgentMonitor;
import org.hyperic.hq.measurement.ext.MeasurementEvent;
import org.hyperic.hq.measurement.monitor.LiveMeasurementException;
import org.hyperic.hq.measurement.monitor.MonitorAgentException;
import org.hyperic.hq.measurement.shared.AvailabilityManager;
import org.hyperic.hq.measurement.shared.MeasurementManager;
import org.hyperic.hq.measurement.shared.MeasurementProcessor;
import org.hyperic.hq.measurement.shared.SRNManager;
import org.hyperic.hq.measurement.shared.TrackerManager;
import org.hyperic.hq.product.MeasurementPluginManager;
import org.hyperic.hq.product.Metric;
import org.hyperic.hq.product.MetricValue;
import org.hyperic.hq.product.ProductPlugin;
import org.hyperic.hq.product.shared.ProductManager;
import org.hyperic.hq.util.Reference;
import org.hyperic.hq.zevents.ZeventManager;
import org.hyperic.util.config.ConfigResponse;
import org.hyperic.util.pager.PageControl;
import org.hyperic.util.timer.StopWatch;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.transaction.annotation.Transactional;

/**
 * The MeasurementManager provides APIs to deal with Measurement objects.
 */
@org.springframework.stereotype.Service
@Transactional
public class MeasurementManagerImpl implements MeasurementManager, ApplicationContextAware,
    ApplicationListener<ResourceDeleteRequestedEvent> {
    private final Log log = LogFactory.getLog(MeasurementManagerImpl.class);
    // XXX scottmf, need to re-evalutate why SAMPLE_SIZE is used
    private static final int SAMPLE_SIZE = 10;

    @Autowired
    private ResourceManager resourceManager;
    @Autowired
    private ResourceGroupManager resourceGroupManager;
    @Autowired
    private ApplicationDAO applicationDAO;
    @Autowired
    private PermissionManager permissionManager;
    @Autowired
    private AuthzSubjectManager authzSubjectManager;
    @Autowired
    private ConfigManager configManager;
    @Autowired
    private MetricDataCache metricDataCache;
    @Autowired
    private MeasurementDAO measurementDAO;
    @Autowired
    private MeasurementTemplateDAO measurementTemplateDAO;
    @Autowired
    private AgentManager agentManager;
    @Autowired
    private AgentMonitor agentMonitor;
    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
	private ZeventManager zeventManager;
    @Autowired
	private SRNManager srnManager;
    @Autowired
	private MeasurementProcessor measurementProcessor;
    @Autowired
	private AvailabilityManager availabilityManager;
    @Autowired
    private MeasurementInserterHolder measurementInserterHolder;
    @Autowired
    private AgentSynchronizer agentSynchronizer;

    // TODO: Resolve circular dependency with ProductManager
    private MeasurementPluginManager getMeasurementPluginManager() throws Exception {
        return (MeasurementPluginManager) applicationContext.getBean(ProductManager.class).getPluginManager(
            ProductPlugin.TYPE_MEASUREMENT);
    }

    /**
     * Translate a template string into a DSN
     */
    private String translate(String tmpl, ConfigResponse config) {
        try {
            return getMeasurementPluginManager().translate(tmpl, config);
        } catch (Exception e) {
            return tmpl;
        }
    }

    /**
     * Enqueue a {@link MeasurementScheduleZevent} on the zevent queue
     * corresponding to the change in schedule for the measurement.
     * 
     * @param dm The Measurement
     * @param interval The new collection interval.
     */
    private void enqueueZeventForMeasScheduleChange(Measurement dm, long interval) {

        MeasurementScheduleZevent event = new MeasurementScheduleZevent(dm.getId().intValue(),
            interval);
        zeventManager.enqueueEventAfterCommit(event);
    }

    /**
     * Enqueue a {@link MeasurementScheduleZevent} on the zevent queue
     * corresponding to collection disabled for the measurements.
     * 
     * @param mids The measurement ids.
     */
    private void enqueueZeventsForMeasScheduleCollectionDisabled(Integer[] mids) {
        List<MeasurementScheduleZevent> events = new ArrayList<MeasurementScheduleZevent>(
            mids.length);

        for (Integer mid : mids) {
            if (mid != null) {
                events.add(new MeasurementScheduleZevent(mid.intValue(), 0));
            }
        }
        zeventManager.enqueueEventsAfterCommit(events);
    }

    private Measurement createMeasurement(Resource instanceId, MeasurementTemplate mt,
                                          ConfigResponse props, long interval)
        throws MeasurementCreateException {
        String dsn = translate(mt.getTemplate(), props);
        return measurementDAO.create(instanceId, mt, dsn, interval);
    }

    /**
     * Remove Measurements that have been deleted from the DataCache
     * @param mids
     */
    private void removeMeasurementsFromCache(Integer[] mids) {

        for (Integer mid : mids) {
            metricDataCache.remove(mid);
        }
    }

    public List<Measurement> createOrUpdateMeasurements(AppdefEntityID id, Integer[] templates, long[] intervals,
                                                        ConfigResponse props, Reference<Boolean> updated)
    throws MeasurementCreateException, TemplateNotFoundException {
        Resource resource = resourceManager.findResource(id);
        if (resource == null || resource.isInAsyncDeleteState()) {
            return Collections.emptyList();
        }

        if (intervals.length != templates.length) {
            throw new IllegalArgumentException("The templates and intervals lists must be the same size");
        }

        MeasurementTemplateDAO tDao = measurementTemplateDAO;
        MeasurementDAO dao = measurementDAO;
        List<Measurement> metrics = dao.findByTemplatesForInstance(templates, resource);

        // Put the metrics in a map for lookup
        Map<Integer, Measurement> lookup = new HashMap<Integer, Measurement>(metrics.size());
        for (Measurement m : metrics) {
            lookup.put(m.getTemplate().getId(), m);
        }

        ArrayList<Measurement> dmList = new ArrayList<Measurement>();
        for (int i = 0; i < templates.length; i++) {
            MeasurementTemplate t = tDao.get(templates[i]);
            if (t == null) {
                continue;
            }
            Measurement m = lookup.get(templates[i]);

            if (m == null) {
                // No measurement, create it
                if (updated != null) {
                    updated.set(true);
                }
                m = createMeasurement(resource, t, props, intervals[i]);
            } else {
                String dsn = translate(m.getTemplate().getTemplate(), props);
                if (updated != null && !updated.get()) {
                    boolean update = m.isEnabled() != (intervals[i] != 0);
                    if (update) updated.set(update);
                    update = m.getInterval() != intervals[i];
                    if (update) updated.set(update);
                    update = !m.getDsn().equals(dsn);
                    if (update) updated.set(update);
                }
                m.setEnabled(intervals[i] != 0);
                m.setInterval(intervals[i]);
                m.setDsn(dsn);
                enqueueZeventForMeasScheduleChange(m, intervals[i]);
            }
            dmList.add(m);
        }

        return dmList;
    }

    /**
     * Create Measurements and enqueue for scheduling after commit
     */
    public List<Measurement> createMeasurements(AuthzSubject subject, AppdefEntityID id,
                                                Integer[] templates, long[] intervals,
                                                ConfigResponse props)
    throws PermissionException, MeasurementCreateException, TemplateNotFoundException {
        // Authz check
        permissionManager.checkModifyPermission(subject.getId(), id);
        Reference<Boolean> updated = new Reference<Boolean>(false);
        List<Measurement> dmList = createOrUpdateMeasurements(id, templates, intervals, props, updated);
        srnManager.scheduleInBackground(Collections.singletonList(id), true, updated.get());
        return dmList;
    }

    /**
     * Create Measurement objects based their templates and default intervals
     * 
     * @param templates List of Integer template IDs to add
     * @param id instance ID (appdef resource) the templates are for
     * @param props Configuration data for the instance
     * 
     * @return a List of the associated Measurement objects
     */
    public List<Measurement> createMeasurements(AuthzSubject subject, AppdefEntityID id,
                                                Integer[] templates, ConfigResponse props)
        throws PermissionException, MeasurementCreateException, TemplateNotFoundException {
        long[] intervals = new long[templates.length];
        for (int i = 0; i < templates.length; i++) {
            MeasurementTemplate tmpl = measurementTemplateDAO.findById(templates[i]);
            intervals[i] = tmpl.getDefaultInterval();
        }

        return createMeasurements(subject, id, templates, intervals, props);
    }

    /**
     */
    @Transactional(readOnly = true)
    public Measurement findMeasurementById(Integer mid) {
        return measurementDAO.findById(mid);
    }
    
    @Transactional(readOnly=true)
    public Collection<MeasurementTemplate> getTemplatesByPrototype(Resource proto) {
        if (proto == null) {
            return Collections.emptyList();
        }
        return measurementTemplateDAO.findTemplatesByMonitorableType(proto.getName());
    }

    @Transactional(readOnly = true)
    public Map<Integer,Measurement> findMeasurementsByIds(final List<Integer> mids) {
        List<Measurement> msmts = new ArrayList<Measurement>(mids.size());
        for(Integer mid:mids) {
            msmts.add(findMeasurementById(mid));
        }
        Map<Integer,Measurement> midToMsmt = new HashMap<Integer,Measurement>();
        for(Measurement msmt:msmts) {
            midToMsmt.put(msmt.getId(), msmt);
        }
        return midToMsmt;
    }
    
    /**
     * Create Measurement objects for an appdef entity based on default
     * templates. This method will only create them if there currently no
     * metrics enabled for the appdef entity.
     * 
     * @param subject Spider subject
     * @param id appdef entity ID of the resource
     * @param mtype The string name of the plugin type
     * @param props Configuration data for the instance
     * 
     * @return a List of the associated Measurement objects
     */
    public List<Measurement> createDefaultMeasurements(AuthzSubject subject, AppdefEntityID id,
                                                        String mtype, ConfigResponse props)
        throws TemplateNotFoundException, PermissionException, MeasurementCreateException {
        // We're going to make sure there aren't metrics already
        List<Measurement> dms = findMeasurements(subject, id, null, PageControl.PAGE_ALL);

        // Find the templates
        Collection<MeasurementTemplate> mts = measurementTemplateDAO
            .findTemplatesByMonitorableType(mtype);

        if (mts.size() == 0 || (dms.size() != 0 && dms.size() == mts.size())) {
            return dms;
        }

        Integer[] tids = new Integer[mts.size()];
        long[] intervals = new long[mts.size()];

        Iterator<MeasurementTemplate> it = mts.iterator();
        for (int i = 0; it.hasNext(); i++) {
            MeasurementTemplate tmpl = it.next();
            tids[i] = tmpl.getId();

            if (tmpl.isDefaultOn())
                intervals[i] = tmpl.getDefaultInterval();
            else
                intervals[i] = 0;
        }

        return createMeasurements(subject, id, tids, intervals, props);
    }

    /**
     * Update the Measurements of a resource
     * 
     */
    private void updateMeasurements(AuthzSubject subject, AppdefEntityID id, ConfigResponse props)
        throws PermissionException, MeasurementCreateException {
        try {
            List<Measurement> all = measurementDAO.findByResource(resourceManager.findResource(id));
            List<Measurement> mcol = new ArrayList<Measurement>();
            for (Measurement dm : all) {
                // Translate all dsns
                dm.setDsn(translate(dm.getTemplate().getTemplate(), props));

                // Now see which Measurements need to be rescheduled
                if (dm.isEnabled()) {
                    mcol.add(dm);
                }
            }

            Integer[] templates = new Integer[mcol.size()];
            long[] intervals = new long[mcol.size()];
            int idx = 0;
            for (Iterator<Measurement> it = mcol.iterator(); it.hasNext(); idx++) {
                Measurement dm = it.next();
                templates[idx] = dm.getTemplate().getId();
                intervals[idx] = dm.getInterval();
            }
            createMeasurements(subject, id, templates, intervals, props);

        } catch (TemplateNotFoundException e) {
            // Would not happen since we're creating measurements with the
            // template that we just looked up
            log.error(e);
        }
    }

    /**
     * Remove all measurements no longer associated with a resource.
     * 
     * @return The number of Measurement objects removed.
     */
    public int removeOrphanedMeasurements() {
        final int MAX_MIDS = 200;

        StopWatch watch = new StopWatch();

        MeasurementDAO dao = measurementDAO;
        List<Integer> mids = dao.findOrphanedMeasurements();

        // Shrink the list down to MAX_MIDS so that we spread out the work over
        // successive data purges
        if (mids.size() > MAX_MIDS) {
            mids = mids.subList(0, MAX_MIDS);
        }

        if (mids.size() > 0) {
            applicationContext.publishEvent(new MetricsDeleteRequestedEvent(mids));
            dao.deleteByIds(mids);
        }

        if (log.isDebugEnabled()) {
            log.debug("MeasurementManager.removeOrphanedMeasurements() " + watch);
        }
        return mids.size();
    }

    /**
     * Look up a Measurement for a Resource and Measurement alias
     * @return a The Measurement for the Resource of the given alias.
     */
    @Transactional(readOnly = true)
    public Measurement getMeasurement(AuthzSubject s, Resource r, String alias)
        throws MeasurementNotFoundException {
        Measurement m = measurementDAO.findByAliasAndID(alias, r);
        if (m == null) {
            throw new MeasurementNotFoundException(alias + " for " + r.getName() + " not found");
        }
        return m;
    }

    /**
     * Get a Measurement by Id.
     */
    @Transactional(readOnly = true)
    public Measurement getMeasurement(Integer mid) {
        return measurementDAO.get(mid);
    }

    /**
     * Get the live measurement values for a given resource.
     * @param id The id of the resource
     */
    @Transactional(readOnly = true)
    public void getLiveMeasurementValues(AuthzSubject subject, AppdefEntityID id)
        throws PermissionException, LiveMeasurementException, MeasurementNotFoundException {
        List<Measurement> mcol = measurementDAO.findEnabledByResource(resourceManager
            .findResource(id));
        String[] dsns = new String[mcol.size()];
        Integer availMeasurement = null; // For insert of AVAIL down

        Iterator<Measurement> it = mcol.iterator();
        for (int i = 0; it.hasNext(); i++) {
            Measurement dm = it.next();
            dsns[i] = dm.getDsn();

            MeasurementTemplate template = dm.getTemplate();

            if (template.getAlias().equals(Metric.ATTR_AVAIL)) {
                availMeasurement = dm.getId();
            }
        }

        log.info("Getting live measurements for " + dsns.length + " measurements");
        try {
            getLiveMeasurementValues(id, dsns, true);
        } catch (LiveMeasurementException e) {
            log.info("Resource " + id + " reports it is unavailable, setting " + "measurement ID " +
                     availMeasurement + " to DOWN: " + e);

            // Only print the full stack trace in debug mode
            if (log.isDebugEnabled()) {
                log.error("Exception details: ", e);
            }

            if (availMeasurement != null) {
                MetricValue val = new MetricValue(MeasurementConstants.AVAIL_DOWN);
                List<DataPoint> l = new ArrayList<DataPoint>(1);
                l.add(new DataPoint(availMeasurement, val));
                DataInserter inserter = measurementInserterHolder.getAvailDataInserter();
                synchronized (inserter.getLock()) {
                	try {
                		inserter.insertMetrics(l);
                	} catch (Exception exp) {
                		log.warn("Problem inserting new availability data for measurement '" + availMeasurement + "'", exp);
                	}
                }
            }
        }
    }

    /**
     * Count of metrics enabled for a particular entity
     * 
     * @return a The number of metrics enabled for the given entity
     */
    @Transactional(readOnly = true)
    public int getEnabledMetricsCount(AuthzSubject subject, AppdefEntityID id) {
        final Resource res = resourceManager.findResource(id);
        if (res == null || res.isInAsyncDeleteState()) {
            return 0;
        }
        final List<Measurement> mcol = measurementDAO.findEnabledByResource(res);
        return mcol.size();
    }

    /**
     * @param subject {@link AuthzSubject}
     * @param resIdsToTemplIds {@link Map} of {@link Integer} of resourceIds to
     *        {@link List} of templateIds
     * @return {@link Map} of {@link Resource} to {@link List} of
     *         {@link Measurement}s
     * @throws PermissionException
     */
    @Transactional(readOnly = true)
    public Map<Resource, List<Measurement>> findMeasurements(
                                                             AuthzSubject subject,
                                                             Map<Integer, List<Integer>> resIdsToTemplIds)
        throws PermissionException {
        Map<Resource, List<Measurement>> rtn = new HashMap<Resource, List<Measurement>>();
        for (Map.Entry<Integer, List<Integer>> entry : resIdsToTemplIds.entrySet()) {
            Integer resId = entry.getKey();
            List<Integer> templs = entry.getValue();
            Integer[] tids = templs.toArray(new Integer[0]);
            Resource resource = resourceManager.findResourceById(resId);
            // checkModifyPermission(subject.getId(), appId);
            Integer resTypeId = resource.getResourceType().getId();
            if (resTypeId.equals(AuthzConstants.authzGroup)) {
                ResourceGroup grp = resourceGroupManager.findResourceGroupById(subject, resource
                    .getInstanceId());
                Collection<Resource> mems = resourceGroupManager.getMembers(grp);
                for (Resource res : mems) {
                    rtn.put(res, measurementDAO.findByTemplatesForInstance(tids, res));
                }
            } else {
                rtn.put(resource, measurementDAO.findByTemplatesForInstance(tids, resource));
            }
        }
        return rtn;
    }
    private List<Measurement> findTemplatesForInstance(final Resource rsc, final Integer[] tids, Map<Integer, Exception> failedResources) {
        List<Measurement> msmts = null;
        try {
            msmts = measurementDAO.findByTemplatesForInstance(tids, rsc);
            if (msmts==null || msmts.isEmpty()) {
                log.error("no measurement templates with the following ids can be assigned to resource " + rsc.getName() + ":\n" + tids);
                failedResources.put(rsc.getId(), null);
            }
        } catch (UnresolvableObjectException e) {
            // don't fail the whole group for a wrong resource IDs
            log.error(e);
            if (failedResources!=null) {
                failedResources.put(rsc.getId(),e);
            }
        }
        return msmts;
    }
    
    /**
     * similar to findMeasurements with the same parameters, with the difference that this one wont fail the whole process on resources which does not exists.
     * It would just not return them. The same goes for measurement names which does not exist.
     */
    @Transactional(readOnly = true)
    public Map<Resource, List<Measurement>> findBulkMeasurements(AuthzSubject subject,
                                                                 Map<Integer, List<Integer>> resIdsToTemplIds,
                                                                 Map<Integer, Exception> failedResources)
                                                                         throws PermissionException {
        Map<Resource, List<Measurement>> rtn = new HashMap<Resource, List<Measurement>>();
        Resource resource = null;
        for (Map.Entry<Integer, List<Integer>> entry : resIdsToTemplIds.entrySet()) {
            Integer rscId = null;
            try {
                rscId = entry.getKey();
                resource = resourceManager.findResourceById(rscId);
                List<Integer> templs = entry.getValue();
                if (templs==null || templs.isEmpty()) { continue; }
                
                Integer[] tids = new Integer[templs.size()];
                templs.toArray(tids);
                
                Integer resTypeId = resource.getResourceType().getId();
                if (resTypeId.equals(AuthzConstants.authzGroup)) {
                    ResourceGroup grp = resourceGroupManager.findResourceGroupById(subject, resource.getInstanceId());
                    Collection<Resource> mems = resourceGroupManager.getMembers(grp);
                    List<Measurement> msmts = null;
                    for (Resource member : mems) {
                        msmts = findTemplatesForInstance(member, tids, failedResources);
                        if (msmts!=null && !msmts.isEmpty()) {
                            rtn.put(member, msmts);
                        }
                    }
                } else {
                    List<Measurement> msmts = findTemplatesForInstance(resource, tids, failedResources);
                    if (msmts!=null && !msmts.isEmpty()) {
                        rtn.put(resource, msmts);
                    }
                }
            } catch (UnresolvableObjectException e) {
                // don't fail the whole request for wrong resource IDs
                log.error(e);
                if (failedResources!=null) {
                    failedResources.put(rscId,e);
                }
                resource = null;
                continue;
            }
        }
        return rtn;
    }
    
    /**
     * Find the Measurement corresponding to the given MeasurementTemplate id
     * and instance id.
     * 
     * @param tid The MeasurementTemplate id
     * @param aeid The entity id.
     * @return a Measurement value
     */
    @Transactional(readOnly = true)
    public Measurement findMeasurement(AuthzSubject subject, Integer tid, AppdefEntityID aeid)
        throws MeasurementNotFoundException {
        List<Measurement> metrics = measurementDAO.findByTemplatesForInstance(
            new Integer[] { tid }, resourceManager.findResource(aeid));

        if (metrics.size() == 0) {
            throw new MeasurementNotFoundException("No measurement found " + "for " + aeid +
                                                   " with " + "template " + tid);
        }
        return metrics.get(0);
    }

    /**
     * Look up a Measurement, allowing for the query to return a stale copy of
     * the Measurement (for efficiency reasons).
     * 
     * @param subject The subject.
     * @param tid The template Id.
     * @param iid The instance Id.
     * @param allowStale <code>true</code> to allow stale copies of an alert
     *        definition in the query results; <code>false</code> to never allow
     *        stale copies, potentially always forcing a sync with the database.
     * @return The Measurement
     */
    @Transactional(readOnly = true)
    public Measurement findMeasurement(AuthzSubject subject, Integer tid, Integer iid,
                                       boolean allowStale) throws MeasurementNotFoundException {

        Measurement dm = measurementDAO.findByTemplateForInstance(tid, iid, allowStale);

        if (dm == null) {
            throw new MeasurementNotFoundException("No measurement found " + "for " + iid +
                                                   " with " + "template " + tid);
        }

        return dm;
    }

    /**
     * Look up a list of Measurements for a template and instances
     * 
     * @return a list of Measurement's
     */
    @Transactional(readOnly = true)
    public List<Measurement> findMeasurements(AuthzSubject subject, Integer tid,
                                              AppdefEntityID[] aeids) {
        ArrayList<Measurement> results = new ArrayList<Measurement>();
        for (AppdefEntityID aeid : aeids) {
            results.addAll(measurementDAO.findByTemplatesForInstance(new Integer[] { tid },
                resourceManager.findResource(aeid)));
        }
        return results;
    }

    /**
     * Look up a list of Measurements for a template and instances
     * 
     * @return An array of Measurement ids.
     */
    @Transactional(readOnly = true)
    public Integer[] findMeasurementIds(AuthzSubject subject, Integer tid, Integer[] ids) {
        List<Integer> results = measurementDAO.findIdsByTemplateForInstances(tid, ids);
        return results.toArray(new Integer[results.size()]);
    }

    /**
     * Look up a list of Measurements for a category XXX: Why is this method
     * called findMeasurements() but only returns enabled measurements if cat ==
     * null??
     * 
     * @return a List of Measurement objects.
     */
    @Transactional(readOnly = true)
    public List<Measurement> findMeasurements(AuthzSubject subject, AppdefEntityID id, String cat,
                                              PageControl pc) {
        List<Measurement> meas;

        // See if category is valid
        if (cat == null || Arrays.binarySearch(MeasurementConstants.VALID_CATEGORIES, cat) < 0) {
            meas = measurementDAO.findEnabledByResource(resourceManager.findResource(id));
        } else {
            meas = measurementDAO.findByResourceForCategory(resourceManager.findResource(id), cat);
        }

        return meas;
    }
    
    /**
     * @param aeids {@link List} of {@link Resource}s
     * @return {@link Map} of {@link Integer} representing resourceId to
     * {@link List} of {@link Measurement}s
     */
    public Map<Integer,List<Measurement>> getEnabledNonAvailMeasurements(List<Resource> resources) {
        return measurementDAO.findEnabledByResources(resources, false);
    }
    
    /**
     * @param aeids {@link List} of {@link Resource}s
     * @return {@link Map} of {@link Integer} representing resourceId to
     * {@link List} of {@link Measurement}s
     */
    public Map<Integer,List<Measurement>> getEnabledMeasurements(List<Resource> resources) {
        return measurementDAO.findEnabledByResources(resources, true);
    }
    
    /**
     * Look up a list of enabled Measurements for a category
     * 
     * @return a list of {@link Measurement}
     */
    @Transactional(readOnly = true)
    public List<Measurement> findEnabledMeasurements(AuthzSubject subject, AppdefEntityID id,
                                                     String cat) {
        List<Measurement> mcol;

        // See if category is valid
        if (cat == null || Arrays.binarySearch(MeasurementConstants.VALID_CATEGORIES, cat) < 0) {
            mcol = measurementDAO.findEnabledByResource(resourceManager.findResource(id));
        } else {
            mcol = measurementDAO.findByResourceForCategory(resourceManager.findResource(id), cat);
        }
        return mcol;
    }

    /**
     * Look up a List of designated Measurements for an entity
     * 
     * @return A List of Measurements
     */
    @Transactional(readOnly = true)
    public List<Measurement> findDesignatedMeasurements(AppdefEntityID id) {
        return measurementDAO.findDesignatedByResource(resourceManager.findResource(id));
    }

    /**
     * Look up a list of designated Measurements for an entity for a category
     * 
     * @return A List of Measurements
     */
    @Transactional(readOnly = true)
    public List<Measurement> findDesignatedMeasurements(AuthzSubject subject, AppdefEntityID id,
                                                        String cat) {
        return measurementDAO.findDesignatedByResourceForCategory(resourceManager.findResource(id),
            cat);
    }

    /**
     * Look up a list of designated Measurements for an group for a category
     * 
     * @return A List of Measurements
     */
    @Transactional(readOnly = true)
    public List<Measurement> findDesignatedMeasurements(AuthzSubject subject, ResourceGroup g,
                                                        String cat) {
        return measurementDAO.findDesignatedByCategoryForGroup(g, cat);
    }
    
    @Transactional(readOnly=true)
    public long getMaxCollectionInterval(ResourceGroup g, Integer templateId) {
        Long max = measurementDAO.getMaxCollectionInterval(g, templateId);

        if (max == null) {
            throw new IllegalArgumentException("Invalid template id =" + templateId + " for resource " + "group " +
                                               g.getId());
        }

        return max.longValue();
    }

  
    @Transactional(readOnly=true)
    public List<Measurement> getMetricsCollecting(ResourceGroup g, Integer templateId) {
        return measurementDAO.getMetricsCollecting(g, templateId);
    }
    
    /**
     * @param aeids {@link List} of {@link AppdefEntityID}s
     * @return {@link Map} of {@link Integer} representing resourceId to
     * {@link List} of {@link Measurement}s
     * 
     */
    public Map<Integer,List<Measurement>> findEnabledMeasurements(Collection<AppdefEntityID> aeids) {
        final List<Resource> resources = new ArrayList<Resource>(aeids.size());
        for (AppdefEntityID aeid : aeids) {
            resources.add(resourceManager.findResource(aeid));
        }
        return measurementDAO.findEnabledByResources(resources, true);
    }

    /**
     * Get an Availabilty Measurement by AppdefEntityId
     * @deprecated Use getAvailabilityMeasurement(Resource) instead.
     * 
     */
    @Deprecated
    @Transactional(readOnly = true)
    public Measurement getAvailabilityMeasurement(AuthzSubject subject, AppdefEntityID id) {
        return getAvailabilityMeasurement(resourceManager.findResource(id));
    }

    /**
     * Get an Availability Measurement by Resource. May return null.
     */
    @Transactional(readOnly = true)
    public Measurement getAvailabilityMeasurement(Resource r) {
        return measurementDAO.findAvailMeasurement(r);
    }

    /**
     * Look up a list of Measurement objects by category
     * 
     */
    @Transactional(readOnly = true)
    public List<Measurement> findMeasurementsByCategory(String cat) {
        return measurementDAO.findByCategory(cat);
    }

    /**
     * Look up a Map of Measurements for a Category
     * 
     * XXX: This method needs to be re-thought. It only returns a single
     * designated metric per category even though HQ supports multiple
     * designates per category.
     * 
     * @return A List of designated Measurements keyed by AppdefEntityID
     * 
     */
    @Transactional(readOnly = true)
    public Map<AppdefEntityID, Measurement> findDesignatedMeasurements(AuthzSubject subject,
                                                                       AppdefEntityID[] ids,
                                                                       String cat)
        throws MeasurementNotFoundException {

        if (ids.length == 0) {
          return new HashMap<AppdefEntityID, Measurement>(0,1);
        }
        Map<AppdefEntityID, Measurement> midMap = new HashMap<AppdefEntityID, Measurement>(ids.length);
        ArrayList<Resource> resources = new ArrayList<Resource>(ids.length);
        for (AppdefEntityID id : ids) {
            resources.add(resourceManager.findResource(id));
        }
        List<Measurement> list = measurementDAO.findDesignatedByResourcesForCategory(resources, cat);
        for (Measurement m:list) {
            midMap.put(AppdefUtil.newAppdefEntityId(m.getResource()), m);
        }
        return midMap;
    }

    /**
     * TODO: scottmf, need to do some more work to handle other authz resource
     * types other than platform, server, service, and group
     * 
     * @return {@link Map} of {@link Integer} to {@link List} of
     *         {@link Measurement}s, Integer => Resource.getId(),
     */
    @Transactional(readOnly = true)
    public Map<Integer, List<Measurement>> getAvailMeasurements(Collection<?> resources) {
        final Map<Integer, List<Measurement>> rtn = new HashMap<Integer, List<Measurement>>(
            resources.size());
        final List<Resource> res = new ArrayList<Resource>(resources.size());
        for (Object o : resources) {
            Resource resource = null;
            if (o == null) {
                continue;
            } else if (o instanceof AppdefEntityValue) {
                AppdefEntityValue rv = (AppdefEntityValue) o;
                AppdefEntityID aeid = rv.getID();
                resource = resourceManager.findResource(aeid);
            } else if (o instanceof AppdefEntityID) {
                AppdefEntityID aeid = (AppdefEntityID) o;
                resource = resourceManager.findResource(aeid);
            } else if (o instanceof AppdefResource) {
                AppdefResource r = (AppdefResource) o;
                resource = resourceManager.findResource(r.getEntityId());
            } else if (o instanceof Resource) {
                resource = (Resource) o;
            } else if (o instanceof ResourceGroup) {
                ResourceGroup grp = (ResourceGroup) o;
                resource = grp.getResource();
                rtn.put(resource.getId(), measurementDAO.findAvailMeasurements(grp));
                continue;
            } else if (o instanceof AppdefResourceValue) {
                AppdefResourceValue r = (AppdefResourceValue) o;
                AppdefEntityID aeid = r.getEntityId();
                resource = resourceManager.findResource(aeid);
            } else {
                resource = resourceManager.findResourceById((Integer) o);
            }
            try {
                if (resource == null || resource.isInAsyncDeleteState()) {
                    continue;
                }
            } catch (ObjectNotFoundException e) {
                continue;
            }
            final ResourceType type = resource.getResourceType();
            if (type.getId().equals(AuthzConstants.authzGroup)) {
                ResourceGroup grp = resourceGroupManager.getResourceGroupByResource(resource);
                rtn.put(resource.getId(), measurementDAO.findAvailMeasurements(grp));
                continue;
            } else if (type.getId().equals(AuthzConstants.authzApplication)) {
                rtn.putAll(getAvailMeas(resource));
                continue;
            }
            res.add(resource);
        }
        List<Measurement> ids = measurementDAO.findAvailMeasurements(res);
        // may be null if measurements have not been configured
        if (ids == null) {
            return Collections.emptyMap();
        }
        for (Measurement m : ids) {
            rtn.put(m.getResource().getId(), Collections.singletonList(m));
        }
        return rtn;
    }

    private Application findApplicationById(AuthzSubject subject, Integer id)
        throws ApplicationNotFoundException, PermissionException {
        try {
            Application app = applicationDAO.findById(id);
            permissionManager.checkViewPermission(subject, app.getEntityId());
            return app;
        } catch (ObjectNotFoundException e) {
            throw new ApplicationNotFoundException(id, e);
        }
    }

    private final Map<Integer, List<Measurement>> getAvailMeas(Resource application) {
        final Integer typeId = application.getResourceType().getId();
        if (!typeId.equals(AuthzConstants.authzApplication)) {
            return Collections.emptyMap();
        }
        final AuthzSubject overlord = authzSubjectManager.getOverlordPojo();
        try {
            final Application app = findApplicationById(overlord, application.getInstanceId());
            final Collection<AppService> appServices = app.getAppServices();
            final List<Resource> resources = new ArrayList<Resource>(appServices.size());
            for (AppService appService : appServices) {
                resources.addAll(getAppResources(appService));
            }
            return getAvailMeasurements(resources);
        } catch (ApplicationNotFoundException e) {
            log.warn("cannot find Application by id = " + application.getInstanceId());
        } catch (PermissionException e) {
            log.error("error finding application using overlord", e);
        }
        return Collections.emptyMap();
    }

    private final List<Resource> getAppResources(AppService appService) {
        if (!appService.isIsGroup()) {
            final Service service = appService.getService();
            if (service == null || service.getResource() == null ||
                service.getResource().isInAsyncDeleteState()) {
                return Collections.emptyList();
            }
            return Collections.singletonList(service.getResource());
        }
        final ResourceGroup group = appService.getResourceGroup();
        final Resource resource = group.getResource();
        if (resource == null || resource.isInAsyncDeleteState()) {
            return Collections.emptyList();
        }
        return new ArrayList<Resource>(resourceGroupManager.getMembers(group));
    }

    /**
     * Look up a list of Measurement intervals for template IDs.
     * 
     * @return a map keyed by template ID and values of metric intervals There
     *         is no entry if a metric is disabled or does not exist for the
     *         given entity or entities. However, if there are multiple
     *         entities, and the intervals differ or some enabled/not enabled,
     *         then the value will be "0" to denote varying intervals.
     */
    @Transactional(readOnly = true)
    public Map<Integer, Long> findMetricIntervals(AuthzSubject subject, AppdefEntityID[] aeids,
                                                  Integer[] tids) {
        final Long disabled = new Long(-1);
        MeasurementDAO ddao = measurementDAO;
        Map<Integer, Long> intervals = new HashMap<Integer, Long>(tids.length);

        for (AppdefEntityID aeid : aeids) {
            Resource res = resourceManager.findResource(aeid);
            List<Measurement> metrics = ddao.findByTemplatesForInstance(tids, res);

            for (Measurement dm : metrics) {
                Long interval = new Long(dm.getInterval());

                if (!dm.isEnabled()) {
                    interval = disabled;
                }

                Integer templateId = dm.getTemplate().getId();
                Long previous = intervals.get(templateId);

                if (previous == null) {
                    intervals.put(templateId, interval);
                } else {
                    if (!previous.equals(interval)) {
                        intervals.put(templateId, new Long(0));
                    }
                }
            }
        }

        // Filter by template IDs, since we only pay attention to what was
        // passed, but may have more than that in our map.
        for (Integer tid : tids) {
            if (!intervals.containsKey(tid)) {
                intervals.put(tid, null);
            }
        }

        // Copy the keys, since we are going to be modifying the interval map
        Set<Integer> keys = new HashSet<Integer>(intervals.keySet());
        for (Integer templateId : keys) {
            if (disabled.equals(intervals.get(templateId))) { // Disabled
                // so don't return it
                intervals.remove(templateId);
            }
        }

        return intervals;
    }

    /**
     * @return List<Object[]> - [0] = Measurement, [1] MeasurementTemplate
     */
    @Transactional(readOnly = true)
    public List<Object[]> findAllEnabledMeasurementsAndTemplates() {
    	log.info("Commencing Measurement cache preload sequence") ;
    	final StopWatch watch = new StopWatch(); 
    	final List<Object[]>  list = measurementDAO.findAllEnabledMeasurementsAndTemplates();
    	log.info("Finished Measurement cache preload equence  of " + list.size() + " entries in " + watch + " seconds") ; 
       return list ; 
    }

    /**
     * Set the interval of Measurements based their template ID's Enable
     * Measurements and enqueue for scheduling after commit
     * 
     */
    @Transactional(readOnly = true)
    public void enableMeasurements(AuthzSubject subject, AppdefEntityID[] aeids, Integer[] mtids,
                                   long interval) throws MeasurementNotFoundException,
        MeasurementCreateException, TemplateNotFoundException, PermissionException {

        // Create a list of IDs
        Integer[] iids = new Integer[aeids.length];
        for (int i = 0; i < aeids.length; i++) {
            permissionManager.checkModifyPermission(subject.getId(), aeids[i]);
            iids[i] = aeids[i].getId();
        }

        List<Integer> mids = new ArrayList<Integer>(aeids.length * mtids.length);
        for (Integer mtid : mtids) {
            mids.addAll(measurementDAO.findIdsByTemplateForInstances(mtid, iids));
        }

        for (Integer mid : mids) {
            final Measurement m = measurementDAO.findById(mid);
            m.setEnabled(true);
            m.setInterval(interval);
        }

        // Update the agent schedule
        List<AppdefEntityID> toSchedule = Arrays.asList(aeids);
        srnManager.scheduleInBackground(toSchedule, true, true);
    }

    /**
     * Enable a collection of metrics, enqueue for scheduling after commit
     */
    public void enableMeasurements(AuthzSubject subject, Integer[] mids) throws PermissionException {
        final StopWatch watch = new StopWatch();
        final List<AppdefEntityID> appIdList = new ArrayList<AppdefEntityID>();
        final List<Integer> midsList = Arrays.asList(mids);
        final boolean debug = log.isDebugEnabled();
        if (debug) watch.markTimeBegin("setEnabled");
        for (Integer mid : midsList) {
            final Measurement meas = measurementDAO.get(mid);
            if (!meas.isEnabled()) {
                final Resource resource = meas.getResource();
                final AppdefEntityID appId = AppdefUtil.newAppdefEntityId(resource);
                permissionManager.checkModifyPermission(subject.getId(), appId);
                appIdList.add(appId);
                meas.setEnabled(true);
            }
        }
        if (debug) watch.markTimeEnd("setEnabled");
        srnManager.scheduleInBackground(appIdList, true, true);
        if (debug) log.debug("enableMeasurements: total=" + appIdList.size() + ", time=" + watch);
    }

    /**
     * Enable the Measurement and enqueue for scheduling after commit
     */
    public void enableMeasurement(AuthzSubject subject, Integer mId, long interval)
        throws PermissionException {
        final List<Integer> mids = Collections.singletonList(mId);
        Measurement meas = measurementDAO.get(mId);
        if (meas.isEnabled()) {
            return;
        }
        Resource resource = meas.getResource();
        AppdefEntityID appId = AppdefUtil.newAppdefEntityId(resource);
        permissionManager.checkModifyPermission(subject.getId(), appId);
        MeasurementDAO dao = measurementDAO;
        for (Integer mid : mids) {
            final Measurement m = dao.findById(mid);
            m.setEnabled(true);
            m.setInterval(interval);
        }
        final List<AppdefEntityID> aeids = Collections.singletonList(appId);
        srnManager.scheduleInBackground(aeids, true, true);
    }

    /**
     * Enable the default on metrics for a given resource, enqueue for
     * scheduling after commit
     */
    public void enableDefaultMeasurements(AuthzSubject subj, Resource r) throws PermissionException {
        AppdefEntityID appId = AppdefUtil.newAppdefEntityId(r);
        permissionManager.checkModifyPermission(subj.getId(), appId);
        boolean sendToAgent = false;

        List<Measurement> metrics = measurementDAO.findDefaultsByResource(r);
        for (Measurement dm : metrics) {
            if (!dm.isEnabled()) {
                dm.setEnabled(true);
                sendToAgent = true;
            }
        }
        if (sendToAgent) {
            List<AppdefEntityID> aeids = Collections.singletonList(appId);
            srnManager.scheduleInBackground(aeids, true, true);
        }
    }

    /**
     * @throws PermissionException
     */
    public void updateMeasurementInterval(AuthzSubject subject, Integer mId, long interval)
        throws PermissionException {
        Measurement meas = measurementDAO.get(mId);
        meas.setEnabled((interval != 0));
        meas.setInterval(interval);
        Resource resource = meas.getResource();
        AppdefEntityID appId = AppdefUtil.newAppdefEntityId(resource);
        permissionManager.checkModifyPermission(subject.getId(), appId);
        enqueueZeventForMeasScheduleChange(meas, interval);
    }

    /**
     * Disable all measurements for the given resources.
     * 
     * @param agentId The entity id to use to look up the agent connection
     * @param ids The list of entitys to unschedule
     * 
     *        NOTE: This method requires all entity ids to be monitored by the
     *        same agent as specified by the agentId
     */
    public void disableMeasurements(AuthzSubject subject, AppdefEntityID agentId,
                                    AppdefEntityID[] ids) throws PermissionException, AgentNotFoundException {
        Agent agent = agentManager.getAgent(agentId);
        disableMeasurements(subject, agent, ids, false);
    }
    
    public void disableMeasurementsForDeletion(AuthzSubject subject, Agent agent,
                    AppdefEntityID[] ids) throws PermissionException {
        if (agent == null || ids == null) {
            return;
        }
        List<Resource> resources = new ArrayList<Resource>();
        for (int i = 0; i < ids.length; i++) {
            permissionManager.checkModifyPermission(subject.getId(), ids[i]);
            resources.add(resourceManager.findResource(ids[i]));
        } 
        List<Measurement> mcol = measurementDAO.findByResources(resources);
        
        Integer[] mids = new Integer[mcol.size()];
        Iterator<Measurement> it = mcol.iterator();
        for (int j = 0; it.hasNext(); j++) {
            Measurement dm = it.next();
            dm.setEnabled(false);
            mids[j] = dm.getId();
        }

        removeMeasurementsFromCache(mids);
        
        enqueueZeventsForMeasScheduleCollectionDisabled(mids);
    
        zeventManager.enqueueEventAfterCommit(new AgentUnscheduleZevent(Arrays.asList(ids), agent.getAgentToken()));
    }
    
    /**
     * Disable all measurements for the given resources.
     *
     * @param agent The agent for the given resources
     * @param ids The list of entitys to unschedule
     * @param isAsyncDelete Indicates whether it is for async delete
     * @ejb:interface-method
     *
     * NOTE: This method requires all entity ids to be monitored by the same
     * agent as specified by the agent
     */
    public void disableMeasurements(AuthzSubject subject, Agent agent,
                                    AppdefEntityID[] ids, boolean isAsyncDelete)
        throws PermissionException {
  
        for (int i = 0; i < ids.length; i++) {
            permissionManager.checkModifyPermission(subject.getId(), ids[i]);
            List<Measurement> mcol = null;
            Resource res = resourceManager.findResource(ids[i]);
            if (isAsyncDelete) {
                // For asynchronous deletes, we need to get all measurements
                // because some disabled measurements are not unscheduled
                // from the agent (like during the maintenance window) and
                // we need to unschedule these measurements
                mcol = findMeasurements(subject, res);
            } else {
                mcol = measurementDAO.findEnabledByResource(res);            
            }
            
            if (mcol.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("No measurements to disable for resource[" 
                                    + ids[i]
                                    + "], isAsyncDelete=" + isAsyncDelete);
                }
                continue;
            }
            
            Integer[] mids = new Integer[mcol.size()];
            Iterator<Measurement> it = mcol.iterator();
            for (int j = 0; it.hasNext(); j++) {
                Measurement dm = it.next();
                dm.setEnabled(false);
                mids[j] = dm.getId();
            }

            removeMeasurementsFromCache(mids);
            
            enqueueZeventsForMeasScheduleCollectionDisabled(mids);
        }
        zeventManager.enqueueEventAfterCommit(new AgentUnscheduleZevent(Arrays.asList(ids), agent.getAgentToken()));
    }


    /**
     * Disable all Measurements for a resource
     * 
     */
    public void disableMeasurements(AuthzSubject subject, AppdefEntityID id)
        throws PermissionException {
        // Authz check
        permissionManager.checkModifyPermission(subject.getId(), id);
        disableMeasurements(subject, resourceManager.findResource(id));
    }

    /**
     * Disable all Measurements for a resource
     * 
     */
    public void disableMeasurements(AuthzSubject subject, Resource res) throws PermissionException {
        List<Measurement> mcol = measurementDAO.findEnabledByResource(res);

        if (mcol.size() == 0) {
            return;
        }

        Integer[] mids = new Integer[mcol.size()];
        Iterator<Measurement> it = mcol.iterator();
        AppdefEntityID aeid = null;
        for (int i = 0; it.hasNext(); i++) {
            Measurement dm = it.next();
            dm.setEnabled(false);
            mids[i] = dm.getId();
            if (aeid == null) {
                aeid = new AppdefEntityID(dm.getTemplate().getMonitorableType().getAppdefType(), dm
                    .getInstanceId());
            }
        }

        removeMeasurementsFromCache(mids);
        enqueueZeventsForMeasScheduleCollectionDisabled(mids);

        // Unscheduling of all metrics for a resource could indicate that
        // the resource is getting removed. Send the unschedule synchronously
        // so that all the necessary plumbing is in place.
        measurementProcessor.unschedule(Collections.singletonList(aeid));
    }

    /**
     * XXX: not sure why all the findMeasurements require an authz if they do
     * not check the viewPermissions??
     */
    @Transactional(readOnly = true)
    public List<Measurement> findMeasurements(AuthzSubject subject, Resource res) {
        return measurementDAO.findByResource(res);
    }

    /**
     * Disable measurements for an instance Enqueues reschedule events after
     * commit
     * 
     */
    public void disableMeasurements(AuthzSubject subject, AppdefEntityID id, Integer[] tids)
        throws PermissionException {
        // Authz check
        permissionManager.checkModifyPermission(subject.getId(), id);

        Resource resource = resourceManager.findResource(id);
        List<Measurement> mcol = measurementDAO.findByResource(resource);
        HashSet<Integer> tidSet = null;
        if (tids != null) {
            tidSet = new HashSet<Integer>(Arrays.asList(tids));
        }

        List<Integer> toUnschedule = new ArrayList<Integer>();
        for (Measurement dm : mcol) {
            // Check to see if we need to remove this one
            if (tidSet != null && !tidSet.contains(dm.getTemplate().getId())) {
                continue;
            }

            dm.setEnabled(false);
            toUnschedule.add(dm.getId());
        }

        Integer[] mids = toUnschedule.toArray(new Integer[toUnschedule.size()]);

        removeMeasurementsFromCache(mids);

        enqueueZeventsForMeasScheduleCollectionDisabled(mids);

        final List<AppdefEntityID> aeids = Collections.singletonList(id);
        srnManager.scheduleInBackground(aeids, true, true);
    }

    /**
     * Disable or enable measurements for a collection of resources
     * during a maintenance window
     */
    public List<DataPoint> enableMeasurements(AuthzSubject admin,
                                              MaintenanceEvent event,
                                              Collection<Resource> resources) {
        final List<DataPoint> rtn = new ArrayList<DataPoint>(resources.size());

        for (Resource resource : resources) {
            // HQ-1653: Only disable/enable availability measurements
            // TODO: G (when AvailabilityManager is convered)
            List<Measurement> measurements =
                availabilityManager.getAvailMeasurementChildren(resource, AuthzConstants.ResourceEdgeContainmentRelation);
            measurements.add(availabilityManager.getAvailMeasurement(resource));
            rtn.addAll(manageAvailabilityMeasurements(event, measurements));
        }
        return rtn;
    }

    /**
     * @return {@link List} of {@link DataPoint}s to insert into db
     *         Disable or enable availability measurements
     */
    private List<DataPoint> manageAvailabilityMeasurements(MaintenanceEvent event,
                                                           Collection<Measurement> measurements) {
        if (measurements == null || measurements.isEmpty()) {
            return Collections.emptyList();
        }
        Integer key = null;
        final List<DataPoint> availDataPoints = new ArrayList<DataPoint>(measurements.size());
        final boolean debug = log.isDebugEnabled();
        final long eventStart = event.getStartTime();
        for (Measurement m : measurements) {
            if (m == null) {
                continue;
            }
            key = m.getId();
            if (!event.getMeasurements().contains(key)) {
                if (event.activate() && !m.isEnabled()) {
                    m.setEnabled(true);
                    if (debug) {
                        log.debug("enabling mid=" + m.getId() +
                                   " for maintenance window end");
                    }
                } else if (!event.activate() && m.isEnabled()) {
                    m.setEnabled(false);
                    if (debug) {
                        log.debug("disabling mid=" + m.getId() +
                                   " for maintenance window begin");
                    }
                    // [HQ-1837] only create "pause" datapoint to mark the
                    // beginning
                    // of the downtime window
                    availDataPoints.add(getPausedDataPoint(m, eventStart));
                }
                event.getMeasurements().add(key);
            } else {
                if (debug) {
                    log.debug("Availability measurement already processed. " +
                               "Skipping measurement [id=" + key + "] for " + event);
                }
            }
        }
        return availDataPoints;
    }

    private final DataPoint getPausedDataPoint(Measurement m, long time) {
        return new DataPoint(m.getId().intValue(), MeasurementConstants.AVAIL_PAUSED, time);
    }

    /**
     */
    public void syncPluginMetrics(String plugin) {
        List<java.lang.Number[]> entities = measurementDAO.findMetricsCountMismatch(plugin);

        AuthzSubject overlord = authzSubjectManager.getOverlordPojo();

        for (java.lang.Number[] vals : entities) {
            java.lang.Number type = vals[0];
            java.lang.Number id = vals[1];
            AppdefEntityID aeid = new AppdefEntityID(type.intValue(), id.intValue());

            try {
                log.info("syncPluginMetrics sync'ing metrics for " + aeid);
                ConfigResponse c = configManager.getMergedConfigResponse(overlord,
                    ProductPlugin.TYPE_MEASUREMENT, aeid, true);
                enableDefaultMetrics(overlord, aeid, c, false);
            } catch (AppdefEntityNotFoundException e) {
                // Move on since we did this query based on measurement table
                // not resource table
            } catch (PermissionException e) {
                // Quite impossible
                assert (false);
            } catch (Exception e) {
                // No valid configuration to use to enable metrics
            }
        }
    }

    /**
     * Gets a summary of the metrics which are scheduled for collection, across
     * all resource types and metrics.
     * 
     * @return a list of {@link CollectionSummary} beans
     */
    @Transactional(readOnly = true)
    public List<CollectionSummary> findMetricCountSummaries() {
        return measurementDAO.findMetricCountSummaries();
    }

    /**
     * Find a list of tuples (of size 4) consisting of the {@link Agent} the
     * {@link Platform} it manages the {@link Server} representing the Agent the
     * {@link Measurement} that contains the Server Offset value
     * 
     */
    @Transactional(readOnly = true)
    public List<Object[]> findAgentOffsetTuples() {
        return measurementDAO.findAgentOffsetTuples();
    }

    /**
     * Get the # of metrics that each agent is collecting.
     * 
     * @return a map of {@link Agent} onto Longs indicating how many metrics
     *         that agent is collecting.
     */
    @Transactional(readOnly = true)
    public Map<Agent, Long> findNumMetricsPerAgent() {
        return measurementDAO.findNumMetricsPerAgent();
    }

    /**
     * Handle events from the {@link MeasurementEnabler}. This method is
     * required to place the operation within a transaction (and session)
     * 
     */
    public void handleCreateRefreshEvents(List<ResourceZevent> events) {

        List<AppdefEntityID> eids = new ArrayList<AppdefEntityID>();
        final boolean debug = log.isDebugEnabled();

        for (ResourceZevent z : events) {
            AuthzSubject subject = authzSubjectManager.findSubjectById(z.getAuthzSubjectId());
            AppdefEntityID id = z.getAppdefEntityID();
            final Resource r = resourceManager.findResource(id);
            if (r == null || r.isInAsyncDeleteState()) {
                continue;
            }
            /**
             * Create - create new measurements and schedule them to the agent
             * Refresh - Simple resend = schedule current measurement to the agent with no update.
             * 	  Most commonly will happen if agent is reinitialized (data dir erased).
             * Update - Config changed - update measurements with new config and schedule to agent.
             */
            boolean isCreate = z instanceof ResourceCreatedZevent;    
            boolean isRefresh = z instanceof ResourceRefreshZevent;
            boolean isUpdate = z instanceof ResourceUpdatedZevent;

            try {
            	if (isCreate) {
            		//If this is the creation of a new measurement we will wait for 2 seconds
            		//so that when we will call the getMergedConfigResponse() method for this resource all
            		//the information will be there. Fix for Jira bug [HQ-3876]
            		try{
            			Thread.sleep(2000);
            		}
            		catch (Exception e) {
            		}
            	}
            	// Handle reschedules for when agents are updated.
            	if (isUpdate) {
                    if (debug) log.debug("Updated metric schedule for [" + id + "]");
                    eids.add(id);
                }
                if (isRefresh) {
                    if(debug) log.debug("Refreshing metric schedule for [" + id + "]");
                    eids.add(id);
                    continue;
                }

                // For either create or update events, schedule the default
                // metrics
                ConfigResponse c = configManager.getMergedConfigResponse(subject,
                    ProductPlugin.TYPE_MEASUREMENT, id, true);
                if (getEnabledMetricsCount(subject, id) == 0) {
                    if (debug) log.debug("Enabling default metrics for [" + id + "]");
                    List<Measurement> metrics = enableDefaultMetrics(subject, id, c, true);
                    if (!metrics.isEmpty()) {
                        eids.add(id);
                    }
                } else {
                    // Update the configuration
                    updateMeasurements(subject, id, c);
                }

                if (isCreate) {
                    // On initial creation of the service check if log or config
                    // tracking is enabled. If so, enable it. We don't auto
                    // enable log or config tracking for update events since
                    // in the callback we don't know if that flag has changed.
                    // TODO break circular dep preventing DI of TrackerManager
                    applicationContext.getBean(TrackerManager.class).enableTrackers(subject, id, c);
                }

            } catch (ConfigFetchException e) {
                log.warn("Config not set for [" + id + "] (this is usually ok): " + e);
            } catch (Exception e) {
                log.warn("Unable to enable default metrics for [" + id + "]", e);
            } 
        }
        srnManager.scheduleInBackground(eids, true, false);
    }
    
    public void setSrnManager(SRNManager srnManager) {
    	this.srnManager = srnManager;
    }
    
    public void setMeasurementDao(MeasurementDAO dao) {
    	this.measurementDAO = dao;
    }
    
	public void setResourceManager(ResourceManager resourceManager){
		this.resourceManager = resourceManager;
	}
	
	public void setMeasurementTemplateDao(MeasurementTemplateDAO mTemplateDao){
		this.measurementTemplateDAO = mTemplateDao; 
	}


    private String[] getTemplatesToCheck(AuthzSubject s, AppdefEntityID id)
        throws AppdefEntityNotFoundException, PermissionException {
        String mType = (new AppdefEntityValue(id, s)).getMonitorableType();
        List<MeasurementTemplate> templates = measurementTemplateDAO.findDefaultsByMonitorableType(
            mType, id.getType());
        List<String> dsnList = new ArrayList<String>(SAMPLE_SIZE);
        int idx = 0;
        int availIdx = -1;
        for (MeasurementTemplate template : templates) {
            if (template.isAvailability() && template.isDesignate()) {
                availIdx = idx;
            }

            if (idx == availIdx || (availIdx == -1 && idx < (SAMPLE_SIZE - 1)) ||
                (availIdx != -1 && idx < SAMPLE_SIZE)) {
                dsnList.add(template.getTemplate());
                // Increment only after we have successfully added DSN
                idx++;
                if (idx >= SAMPLE_SIZE)
                    break;
            }
        }

        return dsnList.toArray(new String[dsnList.size()]);
    }

    /**
     * Check a configuration to see if it returns DSNs which the agent can use
     * to successfully monitor an entity. This routine will attempt to get live
     * DSN values from the entity.
     * 
     * @param entity Entity to check the configuration for
     * @param config Configuration to check
     * 
     */
    @Transactional(readOnly = true)
    public void checkConfiguration(AuthzSubject subject, AppdefEntityID entity, ConfigResponse config, boolean priority)
    throws PermissionException, InvalidConfigException, AppdefEntityNotFoundException {
        String[] templates = getTemplatesToCheck(subject, entity);
        // there are no metric templates, just return
        if (templates.length == 0) {
            log.debug("No metrics to checkConfiguration for " + entity);
            return;
        } else {
            log.debug("Using " + templates.length + " metrics to checkConfiguration for " + entity);
        }
        String[] dsns = new String[templates.length];
        for (int i = 0; i < dsns.length; i++) {
            dsns[i] = translate(templates[i], config);
        }
        try {
            getLiveMeasurementValues(entity, dsns, priority);
        } catch (LiveMeasurementException exc) {
            throw new InvalidConfigException("Invalid configuration: " + exc.getMessage(), exc);
        }
    }

    /**
     * @return List {@link Measurement} of MeasurementIds
     */
    @Transactional(readOnly = true)
    public List<Measurement> getEnabledMeasurements(Integer[] tids, Integer[] aeids) {
        return measurementDAO.findMeasurements(tids, aeids, true);
    }

    /**
     * @return List {@link Measurement} of MeasurementIds
     */
    @Transactional(readOnly = true)
    public List<Measurement> getMeasurements(Integer[] tids, Integer[] aeids) {
        return measurementDAO.findMeasurements(tids, aeids);
    }

    /**
     * Get live measurement values for a series of DSNs
     * 
     * NOTE: Since this routine allows callers to pass in arbitrary DSNs, the
     * caller must do all the appropriate translation, etc.
     * 
     * @param entity Entity to get the measurement values from
     * @param dsns Translated DSNs to fetch from the entity
     * @param priority tells the {@link AgentSynchronizer} to execute this task immediately rather than queuing it.
     * Typically set priority = false for background tasks
     * 
     * @return A list of MetricValue objects for each DSN passed
     */
    private MetricValue[] getLiveMeasurementValues(AppdefEntityID entity, String[] dsns, boolean priority)
    throws LiveMeasurementException, PermissionException {
        try {
            Agent a = agentManager.getAgent(entity);
            LiveValuesAgentDataJob job = new LiveValuesAgentDataJob(a, dsns);
            agentSynchronizer.addAgentJob(job, priority);
            job.waitForJob();
            if (job.wasSuccessful()) {
                return job.values;
            } else {
                throw new LiveMeasurementException(job.failureEx);
            }
        } catch (AgentNotFoundException e) {
            throw new LiveMeasurementException(e.getMessage(), e);
        }
    }
    
    private class LiveValuesAgentDataJob implements AgentDataTransferJob {
        private final String[] dsns;
        private final Agent agent;
        private final AtomicBoolean success = new AtomicBoolean(false);
        private MetricValue[] values;
        private final Object obj = new Object();
        private Exception failureEx;
        private LiveValuesAgentDataJob(final Agent a, final String[] dsns) {
            this.agent = a;
            this.dsns = dsns;
        }
        public boolean wasSuccessful() {
            return success.get();
        }
        public void onFailure() {
        }
        public String getJobDescription() {
            return "getLiveMeasurementValues";
        }
        public int getAgentId() {
            return agent.getId();
        }
        public void execute() {
            try {
                values = agentMonitor.getLiveValues(agent, dsns);
                success.set(true);
            } catch (MonitorAgentException e) {
                success.set(false);
                failureEx = e;
            } catch (LiveMeasurementException e) {
                success.set(false);
                failureEx = e;
            } finally {
                synchronized (obj) {
                    obj.notify();
                }
            }
        }
        private void waitForJob() {
            try {
                synchronized (obj) {
                    obj.wait();
                }
            } catch (InterruptedException e) {
            }
        }
    }

    public void onApplicationEvent(ResourceDeleteRequestedEvent event) {
        measurementDAO.clearResource(event.getResource());
    }

    /**
     * Enable the default metrics for a resource. This should only be called by
     * the {@link MeasurementEnabler}. If you want the behavior of this method,
     * use the {@link MeasurementEnabler}
     *  @return {@link List} of {@link Measurement}s
     */
    private List<Measurement> enableDefaultMetrics(AuthzSubject subj, AppdefEntityID id, ConfigResponse config,
                                                   boolean verify)
    throws AppdefEntityNotFoundException, PermissionException {
        List<Measurement> rtn = new ArrayList<Measurement>(0);
        String mtype;

        try {
            if (id.isPlatform() || id.isServer() || id.isService()) {
                AppdefEntityValue av = new AppdefEntityValue(id, subj);
                try {
                    mtype = av.getMonitorableType();
                } catch (AppdefEntityNotFoundException e) {
                    // Non existent resource, we'll clean it up in
                    // removeOrphanedMeasurements()
                    return rtn;
                }
            } else {
                return rtn;
            }
        } catch (Exception e) {
            log.error("Unable to enable default metrics for [" + id + "]", e);
            return rtn;
        }

        // Check the configuration
        if (verify) {
            try {
                checkConfiguration(subj, id, config, true);
            } catch (InvalidConfigException e) {
                log.warn("Error turning on default metrics, configuration (" + config + ") " +
                         "couldn't be validated", e);
                configManager.setValidationError(subj, id, e.getMessage());
                return rtn;
            } catch (Exception e) {
                log.warn("Error turning on default metrics, " + "error in validation", e);
                configManager.setValidationError(subj, id, e.getMessage());
                return rtn;
            }
        }

        // Enable the metrics
        try {
            rtn = createDefaultMeasurements(subj, id, mtype, config);
            configManager.clearValidationError(subj, id);

            // Publish the event so other people can do things when the
            // metrics have been created (like create type-based alerts)
            applicationContext.publishEvent(new MetricsEnabledEvent(id));
        } catch (Exception e) {
            log.warn("Unable to enable default metrics for id=" + id + ": " + e.getMessage(), e);
        }
        return rtn;
    }

    /**
     * Initializes the units and resource properties of a measurement event
     */
    public void buildMeasurementEvent(MeasurementEvent event) {
        Measurement dm = null;

        try {
            dm = measurementDAO.get(event.getInstanceId());
            int resourceType = dm.getTemplate().getMonitorableType().getAppdefType();
            event.setResource(new AppdefEntityID(resourceType, dm.getInstanceId()));
            event.setUnits(dm.getTemplate().getUnits());
        } catch (Exception e) {
            if (event == null) {
                log.warn("Measurement event is null");
            } else if (dm == null) {
                log.warn("Measurement is null for measurement event with metric id=" +
                         event.getInstanceId());
            } else if (event.getResource() == null) {
                log.error("Unable to set resource for measurement event with metric id=" +
                          event.getInstanceId(), e);
            } else if (event.getUnits() == null) {
                log.error("Unable to set units for measurement event with metric id=" +
                          event.getInstanceId(), e);
            } else {
                log.error("Unable to build measurement event with metric id=" +
                          event.getInstanceId(), e);
            }
        }
    }

    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    public MeasurementTemplate getTemplatesByMeasId(Integer measId) {
        if (measId == null) {
            return null;
        }
        return measurementTemplateDAO.get(measId);
    }
    
    @PreDestroy
    public final void destroy() { 
        this.agentManager = null ; 
        this.agentMonitor = null ; 
        this.applicationContext = null ; 
        this.applicationDAO = null ; 
        this.authzSubjectManager = null ; 
        this.availabilityManager = null ; 
        this.configManager = null ; 
        this.measurementDAO = null ; 
        this.measurementProcessor = null ; 
        this.measurementTemplateDAO = null ; 
        this.metricDataCache = null ; 
        this.permissionManager = null ; 
        this.srnManager = null ; 
        this.zeventManager = null ; 
    }
    
    
}
