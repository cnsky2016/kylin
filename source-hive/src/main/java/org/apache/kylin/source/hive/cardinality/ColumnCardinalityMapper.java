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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.apache.kylin.source.hive.cardinality;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.measure.BufferedMeasureEncoder;
import org.apache.kylin.measure.hllc.HyperLogLogPlusCounter;
import org.apache.kylin.common.util.Bytes;
import org.apache.kylin.cube.kv.RowConstants;
import org.apache.kylin.engine.mr.IMRInput.IMRTableInputFormat;
import org.apache.kylin.engine.mr.KylinMapper;
import org.apache.kylin.engine.mr.MRUtil;
import org.apache.kylin.engine.mr.common.AbstractHadoopJob;
import org.apache.kylin.engine.mr.common.BatchConstants;
import org.apache.kylin.metadata.MetadataManager;
import org.apache.kylin.metadata.model.ColumnDesc;
import org.apache.kylin.metadata.model.TableDesc;

/**
 * @author Jack
 * 
 */
public class ColumnCardinalityMapper<T> extends KylinMapper<T, Object, IntWritable, BytesWritable> {

    private Map<Integer, HyperLogLogPlusCounter> hllcMap = new HashMap<Integer, HyperLogLogPlusCounter>();
    public static final String DEFAULT_DELIM = ",";

    private int counter = 0;

    private TableDesc tableDesc;
    private IMRTableInputFormat tableInputFormat;

    @Override
    protected void setup(Context context) throws IOException {
        Configuration conf = context.getConfiguration();
        bindCurrentConfiguration(conf);
        KylinConfig config = AbstractHadoopJob.loadKylinPropsAndMetadata();

        String tableName = conf.get(BatchConstants.CFG_TABLE_NAME);
        tableDesc = MetadataManager.getInstance(config).getTableDesc(tableName);
        tableInputFormat = MRUtil.getTableInputFormat(tableDesc);
    }

    @Override
    public void map(T key, Object value, Context context) throws IOException, InterruptedException {
        ColumnDesc[] columns = tableDesc.getColumns();
        String[] values = tableInputFormat.parseMapperInput(value);

        for (int m = 0; m < columns.length; m++) {
            String field = columns[m].getName();
            String fieldValue = values[m];
            if (fieldValue == null)
                fieldValue = "NULL";

            if (counter < 5 && m < 10) {
                System.out.println("Get row " + counter + " column '" + field + "'  value: " + fieldValue);
            }

            if (fieldValue != null)
                getHllc(m).add(Bytes.toBytes(fieldValue.toString()));
        }

        counter++;
    }

    private HyperLogLogPlusCounter getHllc(Integer key) {
        if (!hllcMap.containsKey(key)) {
            hllcMap.put(key, new HyperLogLogPlusCounter());
        }
        return hllcMap.get(key);
    }

    @Override
    protected void cleanup(Context context) throws IOException, InterruptedException {
        Iterator<Integer> it = hllcMap.keySet().iterator();
        ByteBuffer buf = ByteBuffer.allocate(BufferedMeasureEncoder.DEFAULT_BUFFER_SIZE);
        while (it.hasNext()) {
            int key = it.next();
            HyperLogLogPlusCounter hllc = hllcMap.get(key);
            buf.clear();
            hllc.writeRegisters(buf);
            buf.flip();
            context.write(new IntWritable(key), new BytesWritable(buf.array(), buf.limit()));
        }
    }

}
