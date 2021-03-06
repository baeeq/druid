/*
 * Druid - a distributed column store.
 * Copyright (C) 2012  Metamarkets Group Inc.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package com.metamx.druid.db;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.metamx.common.concurrent.ScheduledExecutors;
import com.metamx.common.lifecycle.LifecycleStart;
import com.metamx.common.lifecycle.LifecycleStop;
import com.metamx.common.logger.Logger;
import com.metamx.druid.TimelineObjectHolder;
import com.metamx.druid.VersionedIntervalTimeline;
import com.metamx.druid.client.DataSegment;
import com.metamx.druid.client.DruidDataSource;
import org.codehaus.jackson.map.ObjectMapper;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.joda.time.Interval;
import org.skife.jdbi.v2.Batch;
import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.FoldController;
import org.skife.jdbi.v2.Folder3;
import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.StatementContext;
import org.skife.jdbi.v2.tweak.HandleCallback;

import javax.annotation.Nullable;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

/**
 */
public class DatabaseSegmentManager
{
  private static final Logger log = new Logger(DatabaseSegmentManager.class);

  private final Object lock = new Object();

  private final ObjectMapper jsonMapper;
  private final ScheduledExecutorService exec;
  private final DatabaseSegmentManagerConfig config;
  private final AtomicReference<ConcurrentHashMap<String, DruidDataSource>> dataSources;
  private final DBI dbi;

  private volatile boolean started = false;

  public DatabaseSegmentManager(
      ObjectMapper jsonMapper,
      ScheduledExecutorService exec,
      DatabaseSegmentManagerConfig config,
      DBI dbi
  )
  {
    this.jsonMapper = jsonMapper;
    this.exec = exec;
    this.config = config;
    this.dataSources = new AtomicReference<ConcurrentHashMap<String, DruidDataSource>>(
        new ConcurrentHashMap<String, DruidDataSource>()
    );
    this.dbi = dbi;
  }

  @LifecycleStart
  public void start()
  {
    synchronized (lock) {
      if (started) {
        return;
      }

      ScheduledExecutors.scheduleWithFixedDelay(
          exec,
          new Duration(0),
          config.getPollDuration(),
          new Runnable()
          {
            @Override
            public void run()
            {
              poll();
            }
          }
      );

      started = true;
    }
  }

  @LifecycleStop
  public void stop()
  {
    synchronized (lock) {
      if (!started) {
        return;
      }

      dataSources.set(new ConcurrentHashMap<String, DruidDataSource>());

      started = false;
    }
  }

  public boolean enableDatasource(final String ds)
  {
    try {
      VersionedIntervalTimeline<String, DataSegment> segmentTimeline = dbi.withHandle(
          new HandleCallback<VersionedIntervalTimeline<String, DataSegment>>()
          {
            @Override
            public VersionedIntervalTimeline<String, DataSegment> withHandle(Handle handle) throws Exception
            {
              return handle.createQuery(
                  String.format("SELECT payload FROM %s WHERE dataSource = :dataSource", config.getSegmentTable())
              )
                           .bind("dataSource", ds)
                           .fold(
                               new VersionedIntervalTimeline<String, DataSegment>(Ordering.natural()),
                               new Folder3<VersionedIntervalTimeline<String, DataSegment>, Map<String, Object>>()
                               {
                                 @Override
                                 public VersionedIntervalTimeline<String, DataSegment> fold(
                                     VersionedIntervalTimeline<String, DataSegment> timeline,
                                     Map<String, Object> stringObjectMap,
                                     FoldController foldController,
                                     StatementContext statementContext
                                 ) throws SQLException
                                 {
                                   try {
                                     DataSegment segment = jsonMapper.readValue(
                                         (String) stringObjectMap.get("payload"),
                                         DataSegment.class
                                     );

                                     timeline.add(
                                         segment.getInterval(),
                                         segment.getVersion(),
                                         segment.getShardSpec().createChunk(segment)
                                     );

                                     return timeline;
                                   }
                                   catch (Exception e) {
                                     throw new SQLException(e.toString());
                                   }
                                 }
                               }
                           );
            }
          }
      );

      final List<DataSegment> segments = Lists.transform(
          segmentTimeline.lookup(new Interval(new DateTime(0), new DateTime("3000-01-01"))),
          new Function<TimelineObjectHolder<String, DataSegment>, DataSegment>()
          {
            @Override
            public DataSegment apply(@Nullable TimelineObjectHolder<String, DataSegment> input)
            {
              return input.getObject().getChunk(0).getObject();
            }
          }
      );

      if (segments.isEmpty()) {
        log.warn("No segments found in the database!");
        return false;
      }

      dbi.withHandle(
          new HandleCallback<Void>()
          {
            @Override
            public Void withHandle(Handle handle) throws Exception
            {
              Batch batch = handle.createBatch();

              for (DataSegment segment : segments) {
                batch.add(
                    String.format(
                        "UPDATE %s SET used=1 WHERE id = '%s'",
                        config.getSegmentTable(),
                        segment.getIdentifier()
                    )
                );
              }
              batch.execute();

              return null;
            }
          }
      );
    }
    catch (Exception e) {
      log.error(e, "Exception enabling datasource %s", ds);
      return false;
    }

    return true;
  }

  public boolean enableSegment(final String segmentId)
  {
    try {
      dbi.withHandle(
          new HandleCallback<Void>()
          {
            @Override
            public Void withHandle(Handle handle) throws Exception
            {
              handle.createStatement(
                  String.format("UPDATE %s SET used=1 WHERE id = :id", config.getSegmentTable())
              )
                    .bind("id", segmentId)
                    .execute();
              return null;
            }
          }
      );
    }
    catch (Exception e) {
      log.error(e, "Exception enabling segment %s", segmentId);
      return false;
    }

    return true;
  }


  public boolean removeDatasource(final String ds)
  {
    try {
      ConcurrentHashMap<String, DruidDataSource> dataSourceMap = dataSources.get();

      if (!dataSourceMap.containsKey(ds)) {
        log.warn("Cannot delete datasource %s, does not exist", ds);
        return false;
      }

      dbi.withHandle(
          new HandleCallback<Void>()
          {
            @Override
            public Void withHandle(Handle handle) throws Exception
            {
              handle.createStatement(
                  String.format("UPDATE %s SET used=0 WHERE dataSource = :dataSource", config.getSegmentTable())
              )
                    .bind("dataSource", ds)
                    .execute();

              return null;
            }
          }
      );

      dataSourceMap.remove(ds);
    }
    catch (Exception e) {
      log.error(e, "Error removing datasource %s", ds);
      return false;
    }

    return true;
  }

  public boolean removeSegment(String ds, final String segmentID)
  {
    try {
      dbi.withHandle(
          new HandleCallback<Void>()
          {
            @Override
            public Void withHandle(Handle handle) throws Exception
            {
              handle.createStatement(
                  String.format("UPDATE %s SET used=0 WHERE id = :segmentID", config.getSegmentTable())
              ).bind("segmentID", segmentID)
                    .execute();

              return null;
            }
          }
      );

      ConcurrentHashMap<String, DruidDataSource> dataSourceMap = dataSources.get();

      if (!dataSourceMap.containsKey(ds)) {
        log.warn("Cannot find datasource %s", ds);
        return false;
      }

      DruidDataSource dataSource = dataSourceMap.get(ds);
      dataSource.removePartition(segmentID);

      if (dataSource.isEmpty()) {
        dataSourceMap.remove(ds);
      }
    }
    catch (Exception e) {
      log.error(e, e.toString());
      return false;
    }

    return true;
  }

  public boolean isStarted()
  {
    return started;
  }

  public DruidDataSource getInventoryValue(String key)
  {
    return dataSources.get().get(key);
  }

  public Collection<DruidDataSource> getInventory()
  {
    return dataSources.get().values();
  }

  public void poll()
  {
    try {
      ConcurrentHashMap<String, DruidDataSource> newDataSources
          = new ConcurrentHashMap<String, DruidDataSource>();

      List<Map<String, Object>> segmentRows = dbi.withHandle(
          new HandleCallback<List<Map<String, Object>>>()
          {
            @Override
            public List<Map<String, Object>> withHandle(Handle handle) throws Exception
            {
              return handle.createQuery(
                  String.format("SELECT payload FROM %s WHERE used=1", config.getSegmentTable())
              ).list();
            }
          }
      );

      if (segmentRows == null || segmentRows.isEmpty()) {
        log.warn("No segments found in the database!");
        return;
      }

      log.info("Polled and found %,d segments in the database", segmentRows.size());

      for (Map<String, Object> segmentRow : segmentRows) {
        DataSegment segment = jsonMapper.readValue((String) segmentRow.get("payload"), DataSegment.class);

        String datasourceName = segment.getDataSource();

        DruidDataSource dataSource = newDataSources.get(datasourceName);
        if (dataSource == null) {
          dataSource = new DruidDataSource(
              datasourceName,
              ImmutableMap.of("created", new DateTime().toString())
          );

          Object shouldBeNull = newDataSources.put(
              datasourceName,
              dataSource
          );
          if (shouldBeNull != null) {
            log.warn(
                "Just put key[%s] into dataSources and what was there wasn't null!?  It was[%s]",
                datasourceName,
                shouldBeNull
            );
          }
        }

        if (!dataSource.getSegments().contains(segment)) {
          dataSource.addSegment(segment.getIdentifier(), segment);
        }
      }

      dataSources.set(newDataSources);
    }
    catch (Exception e) {
      log.error(e, e.toString());
    }
  }
}
