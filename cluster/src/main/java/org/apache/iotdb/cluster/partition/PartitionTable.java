/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.cluster.partition;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.collections4.map.MultiKeyMap;
import org.apache.iotdb.cluster.config.ClusterDescriptor;
import org.apache.iotdb.cluster.exception.UnsupportedPlanException;
import org.apache.iotdb.cluster.log.Log;
import org.apache.iotdb.cluster.log.logtypes.PhysicalPlanLog;
import org.apache.iotdb.cluster.rpc.thrift.Node;
import org.apache.iotdb.cluster.utils.PartitionUtils;
import org.apache.iotdb.db.exception.metadata.MetadataException;
import org.apache.iotdb.db.exception.metadata.StorageGroupNotSetException;
import org.apache.iotdb.db.metadata.MManager;
import org.apache.iotdb.db.qp.physical.PhysicalPlan;
import org.apache.iotdb.db.qp.physical.crud.BatchInsertPlan;
import org.apache.iotdb.db.qp.physical.crud.DeletePlan;
import org.apache.iotdb.db.qp.physical.crud.InsertPlan;
import org.apache.iotdb.db.qp.physical.sys.CreateTimeSeriesPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PartitionTable manages the map whose key is the StorageGroupName with a time interval and the
 * value is a PartitionGroup with contains all nodes that manage the corresponding data.
 */
public interface PartitionTable {
  // static final is not necessary, it is redundant for an interface
  Logger logger = LoggerFactory.getLogger(SlotPartitionTable.class);
  long PARTITION_INTERVAL = ClusterDescriptor.getINSTANCE().getConfig().getPartitionInterval();

  /**
   * Given the storageGroupName and the timestamp, return the list of nodes on which the storage
   * group and the corresponding time interval is managed.
   * @param storageGroupName
   * @param timestamp
   * @return
   */
  PartitionGroup route(String storageGroupName, long timestamp);

  /**
   * Add a new node to update the partition table.
   * @param node
   * @return the new group generated by the node
   */
  PartitionGroup addNode(Node node);

  /**
   *
   * @return All data groups where all VNodes of this node is the header. The first index
   * indicates the VNode and the second index indicates the data group of one VNode.
   */
  List<PartitionGroup> getLocalGroups();

  /**
   *
   * @param header
   * @return the partition group starting from the header.
   */
  PartitionGroup getHeaderGroup(Node header);

  ByteBuffer serialize();

  void deserialize(ByteBuffer buffer);

  List<Node> getAllNodes();

  /**
   *
   * @return each slot's previous holder after the node's addition.
   */
  Map<Integer, Node> getPreviousNodeMap(Node node);

  /**
   *
   * @param header
   * @return the slots held by the header.
   */
  List<Integer> getNodeSlots(Node header);

  Map<Node, List<Integer>> getAllNodeSlots();

  int getSlotNum();

  default int calculateLogSlot(Log log) {
    if (log instanceof PhysicalPlanLog) {
      PhysicalPlanLog physicalPlanLog = ((PhysicalPlanLog) log);
      PhysicalPlan plan = physicalPlanLog.getPlan();
      String storageGroup = null;
      if (plan instanceof CreateTimeSeriesPlan) {
        try {
          storageGroup = MManager.getInstance()
              .getStorageGroupNameByPath(((CreateTimeSeriesPlan) plan).getPath().getFullPath());
          return PartitionUtils.calculateStorageGroupSlot(storageGroup, 0, this.getSlotNum());
        } catch (MetadataException e) {
          logger.error("Cannot find the storage group of {}", ((CreateTimeSeriesPlan) plan).getPath());
          return -1;
        }
      } else if (plan instanceof InsertPlan || plan instanceof BatchInsertPlan) {
        try {
          storageGroup = MManager.getInstance()
              .getStorageGroupNameByPath(((InsertPlan) plan).getDeviceId());
        } catch (StorageGroupNotSetException e) {
          logger.error("Cannot find the storage group of {}", ((CreateTimeSeriesPlan) plan).getPath());
          return -1;
        }
      } else if (plan instanceof DeletePlan) {
        //TODO deleteplan may have many SGs.
        logger.error("not implemented for DeletePlan in cluster {}", plan);
        return -1;
      }

      return Math.abs(Objects.hash(storageGroup, 0));
    }
    return 0;
  }

  default PartitionGroup partitionPlan(PhysicalPlan plan)
      throws UnsupportedPlanException {
    // TODO-Cluster#348: support more plans
    try {
      if (plan instanceof CreateTimeSeriesPlan) {
        CreateTimeSeriesPlan createTimeSeriesPlan = ((CreateTimeSeriesPlan) plan);
        return partitionByPathTime(createTimeSeriesPlan.getPath().getFullPath(), 0);
      } else if (plan instanceof InsertPlan) {
        InsertPlan insertPlan = ((InsertPlan) plan);
        return partitionByPathTime(insertPlan.getDeviceId(), 0);
        // TODO-Cluster#350: use time in partitioning
        // return partitionByPathTime(insertPlan.getDeviceId(), insertPlan.getTime(),
        // partitionTable);
      }
    } catch (StorageGroupNotSetException e) {
      logger.debug("Storage group is not found for plan {}", plan);
      return null;
    }
    logger.error("Unable to partition plan {}", plan);
    throw new UnsupportedPlanException(plan);
  }

  default PartitionGroup partitionByPathTime(String path, long timestamp)
      throws StorageGroupNotSetException {
    String storageGroup = MManager.getInstance().getStorageGroupNameByPath(path);
    return this.route(storageGroup, timestamp);
  }

  /**
   * Get partition info by path and range time
   *
   * @UsedBy NodeTool
   */
  default  MultiKeyMap<Long, PartitionGroup> partitionByPathRangeTime(String path,
      long startTime, long endTime) throws StorageGroupNotSetException {
    MultiKeyMap<Long, PartitionGroup> timeRangeMapRaftGroup = new MultiKeyMap<>();
    String storageGroup = MManager.getInstance().getStorageGroupNameByPath(path);
    while (startTime <= endTime) {
      long nextTime = (startTime / PARTITION_INTERVAL + 1) * PARTITION_INTERVAL; //FIXME considering the time unit
      timeRangeMapRaftGroup.put(startTime, Math.min(nextTime - 1, endTime),
          this.route(storageGroup, startTime));
      startTime = nextTime;
    }
    return timeRangeMapRaftGroup;
  }
}
