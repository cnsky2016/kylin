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

package org.apache.kylin.cube;

import org.apache.kylin.cube.model.CubeDesc;
import org.apache.kylin.cube.model.DimensionDesc;
import org.apache.kylin.metadata.model.FunctionDesc;
import org.apache.kylin.metadata.model.JoinDesc;
import org.apache.kylin.metadata.model.MeasureDesc;
import org.apache.kylin.metadata.model.TblColRef;
import org.apache.kylin.metadata.realization.SQLDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 */
public class CubeCapabilityChecker {
    private static final Logger logger = LoggerFactory.getLogger(CubeCapabilityChecker.class);

    public static boolean check(CubeInstance cube, SQLDigest digest, boolean allowWeakMatch) {

        // retrieve members from olapContext
        Collection<TblColRef> dimensionColumns = CubeDimensionDeriver.getDimensionColumns(digest);
        Collection<FunctionDesc> functions = digest.aggregations;
        Collection<TblColRef> metricsColumns = digest.metricColumns;
        Collection<JoinDesc> joins = digest.joinDescs;
        boolean hasTopN = hasTopNMeasure(cube.getDescriptor());

        // match dimensions & aggregations & joins

        boolean isOnline = cube.isReady();

        boolean matchDimensions = isMatchedWithDimensions(dimensionColumns, cube);
        boolean matchAggregation = isMatchedWithAggregations(functions, cube);
        boolean matchJoin = isMatchedWithJoins(joins, cube);

        // Some cubes are not "perfectly" match, but still save them in case of usage
        if (allowWeakMatch && isOnline && matchDimensions && !matchAggregation && matchJoin) {
            // sometimes metrics are indeed dimensions
            // e.g. select min(cal_dt) from ..., where cal_dt is actually a dimension
            if (isWeaklyMatchedWithAggregations(functions, metricsColumns, cube)) {
                logger.info("Weakly matched cube found " + cube.getName());
                return true;
            }
        }

        // for topn, the group column can come from measure
        if (hasTopN & matchJoin && !matchDimensions && functions.size() == 1) {
            boolean matchedTopN = isMatchedWithTopN(dimensionColumns, cube, digest);
            matchDimensions = matchedTopN;
            matchAggregation = matchedTopN;
        }

        if (!isOnline || !matchDimensions || !matchAggregation || !matchJoin) {
            logger.info("Exclude cube " + cube.getName() + " because " + " isOnlne=" + isOnline + ",matchDimensions=" + matchDimensions + ",matchAggregation=" + matchAggregation + ",matchJoin=" + matchJoin);
            return false;
        }

        return true;
    }

    private static boolean isMatchedWithTopN(Collection<TblColRef> dimensionColumns, CubeInstance cube, SQLDigest digest) {

        CubeDesc cubeDesc = cube.getDescriptor();
        List<FunctionDesc> cubeFunctions = cubeDesc.listAllFunctions();
        Collection<FunctionDesc> functions = digest.aggregations;
        Collection<MeasureDesc> sortMeasures = digest.sortMeasures;
        Collection<SQLDigest.OrderEnum> sortOrders = digest.sortOrders;

        FunctionDesc onlyFunction = functions.iterator().next();
        if (onlyFunction.isSum() == false) {
            // topN only support SUM expression
            return false;
        }

        Collection<TblColRef> dimensionColumnsCopy = new ArrayList<TblColRef>(dimensionColumns);
        for (MeasureDesc measure : cubeDesc.getMeasures()) {
            if (measure.getFunction().isTopN()) {
                List<TblColRef> cols = measure.getFunction().getParameter().getColRefs();
                TblColRef displayCol = cols.get(cols.size() - 1);
                dimensionColumnsCopy.remove(displayCol);
                if(isMatchedWithDimensions(dimensionColumnsCopy, cube)) {
                    if (measure.getFunction().isCompatible(onlyFunction)) {
                        return true;
                    }
                }
                dimensionColumnsCopy.add(displayCol);
            }
        }

        return false;
    }

    private static boolean hasTopNMeasure(CubeDesc cubeDesc) {
        for (MeasureDesc measureDesc : cubeDesc.getMeasures()) {
            if (measureDesc.getFunction().isTopN())
                return true;
        }

        return false;
    }

    private static boolean isMatchedWithDimensions(Collection<TblColRef> dimensionColumns, CubeInstance cube) {
        CubeDesc cubeDesc = cube.getDescriptor();
        boolean matchAgg = cubeDesc.listDimensionColumnsIncludingDerived().containsAll(dimensionColumns);
        return matchAgg;
    }

    private static boolean isMatchedWithAggregations(Collection<FunctionDesc> aggregations, CubeInstance cube) {
        CubeDesc cubeDesc = cube.getDescriptor();
        boolean matchAgg = cubeDesc.listAllFunctions().containsAll(aggregations);
        return matchAgg;
    }

    private static boolean isMatchedWithJoins(Collection<JoinDesc> joins, CubeInstance cube) {
        CubeDesc cubeDesc = cube.getDescriptor();

        List<JoinDesc> cubeJoins = new ArrayList<JoinDesc>(cubeDesc.getDimensions().size());
        for (DimensionDesc d : cubeDesc.getDimensions()) {
            if (d.getJoin() != null) {
                cubeJoins.add(d.getJoin());
            }
        }
        for (JoinDesc j : joins) {
            // optiq engine can't decide which one is fk or pk
            String pTable = j.getPrimaryKeyColumns()[0].getTable();
            String factTable = cubeDesc.getFactTable();
            if (factTable.equals(pTable)) {
                j.swapPKFK();
            }

            // check primary key, all PK column should refer to same tale, the Fact Table of cube.
            // Using first column's table name to check.
            String fTable = j.getForeignKeyColumns()[0].getTable();
            if (!factTable.equals(fTable)) {
                logger.info("Fact Table" + factTable + " not matched in join: " + j + " on cube " + cube.getName());
                return false;
            }

            // The hashcode() function of JoinDesc has been overwritten,
            // which takes into consideration: pk,fk,jointype
            if (!cubeJoins.contains(j)) {
                logger.info("Query joins don't macth on cube " + cube.getName());
                return false;
            }
        }
        return true;
    }

    private static boolean isWeaklyMatchedWithAggregations(Collection<FunctionDesc> aggregations, Collection<TblColRef> metricColumns, CubeInstance cube) {
        CubeDesc cubeDesc = cube.getDescriptor();
        Collection<FunctionDesc> cubeFuncs = cubeDesc.listAllFunctions();

        boolean matched = true;
        for (FunctionDesc functionDesc : aggregations) {
            if (cubeFuncs.contains(functionDesc))
                continue;

            // only inverted-index cube does not have count, and let calcite handle in this case
            if (functionDesc.isCount())
                continue;

            if (functionDesc.isCountDistinct()) // calcite can not handle distinct count
                matched = false;

            TblColRef col = null;
            if (functionDesc.getParameter().getColRefs().size() > 0)
                col = functionDesc.getParameter().getColRefs().get(0);

            if (col == null || !cubeDesc.listDimensionColumnsIncludingDerived().contains(col)) {
                matched = false;
            }
        }
        return matched;
    }
}