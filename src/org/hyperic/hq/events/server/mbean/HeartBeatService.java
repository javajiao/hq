/*
 * NOTE: This copyright does *not* cover user programs that use HQ
 * program services by normal system calls through the application
 * program interfaces provided as part of the Hyperic Plug-in Development
 * Kit or the Hyperic Client Development Kit - this is merely considered
 * normal use of the program, and does *not* fall under the heading of
 * "derived work".
 * 
 * Copyright (C) [2004, 2005, 2006], Hyperic, Inc.
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

package org.hyperic.hq.events.server.mbean;

import java.util.Date;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hyperic.hq.common.SessionMBeanBase;
import org.hyperic.hq.common.util.Messenger;
import org.hyperic.hq.events.EventConstants;
import org.hyperic.hq.events.HeartBeatEvent;
import org.hyperic.hq.events.shared.RegisteredTriggerManagerUtil;
import org.hyperic.hq.zevents.ZeventManager;

/**
 * MBean class that is called by the Scheduler to send a HeartBeat
 * event.
 *
 * @jmx:mbean name="hyperic.jmx:type=Service,name=EventsHeartBeat"
 */
public class HeartBeatService 
    extends SessionMBeanBase
    implements HeartBeatServiceMBean 
{
    private final Log log = LogFactory.getLog(HeartBeatService.class);
    
    private String topicName = EventConstants.EVENTS_TOPIC;

    /**
     * Get the topic name to where the heartbeat messages will be
     * sent.
     *
     * @jmx:managed-attribute
     */
    public String getTopicName() {
        return topicName;
    }

    /**
     * Set the topic name to where the heartbeat messages will be
     * sent.
     *
     * @jmx:managed-attribute
     */
    public void setTopicName(String topicName) {
        this.topicName = topicName;
    }

    /**
     * Send the message.
     *
     * @jmx:managed-operation
     */
    public void hit(Date lDate) {
        super.hit(lDate);
    }
    
    protected void hitInSession(Date lDate) {
        if (log.isDebugEnabled()) {
            log.debug("Heart Beat Service is dispatching a heart beat: "+lDate+
                      ", timestamp="+lDate.getTime());            
        }
        
        HeartBeatEvent event = new HeartBeatEvent(lDate);
        
        try {
            // Try to see if RegisteredTriggerManager is available
            RegisteredTriggerManagerUtil.getLocalHome();
            
            // Send the heart beat event
            Messenger sender = new Messenger();
            sender.publishMessage(topicName, event);
        } catch (Exception e) {
            // Do not send out heart beat if services are not up
        }
        
        try {
            ZeventManager.getInstance().enqueueEvent(event.toZevent());
        } catch(InterruptedException e) {
            // Do not send out heart beat if thread is interrupted
        }
    }

    /**
     * @jmx:managed-operation
     */
    public void init() {}

    /**
     * @jmx:managed-operation
     */
    public void start() {}

    /**
     * @jmx:managed-operation
     */
    public void stop() {}

    /**
     * @jmx:managed-operation
     */
    public void destroy() {}
}
