/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysml.runtime.controlprogram.paramserv.dp;

import java.io.Serializable;
import java.util.LinkedList;

import org.apache.spark.api.java.function.PairFunction;
import org.apache.sysml.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysml.runtime.matrix.data.MatrixBlock;
import org.apache.sysml.utils.IntUtils;

import scala.Tuple2;

public class DataPartitionerSparkAggregator implements PairFunction<Tuple2<Integer,LinkedList<Tuple2<Long,Tuple2<MatrixBlock,MatrixBlock>>>>, Integer, Tuple2<MatrixBlock, MatrixBlock>>, Serializable {

	private static final long serialVersionUID = -1245300852709085117L;
	private long _fcol;
	private long _lcol;

	public DataPartitionerSparkAggregator() {

	}

	public DataPartitionerSparkAggregator(long fcol, long lcol) {
		_fcol = fcol;
		_lcol = lcol;
	}

	/**
	 * Row-wise combine the matrix
	 * @param input workerID => ordered list [(rowBlockID, (features, labels))]
	 * @return workerID => [(features, labels)]
	 * @throws Exception Some exception
	 */
	@Override
	public Tuple2<Integer, Tuple2<MatrixBlock, MatrixBlock>> call(Tuple2<Integer, LinkedList<Tuple2<Long, Tuple2<MatrixBlock, MatrixBlock>>>> input) throws Exception {
		MatrixBlock fmb = new MatrixBlock(input._2.size(), IntUtils.toInt( _fcol ), false);
		MatrixBlock lmb = new MatrixBlock(input._2.size(), IntUtils.toInt( _lcol ), false);

		for (int i = 0; i < input._2.size(); i++) {
			MatrixBlock tmpFMB = input._2.get(i)._2._1;
			MatrixBlock tmpLMB = input._2.get(i)._2._2;
			// Row-wise aggregation
			fmb = fmb.leftIndexingOperations(tmpFMB, i, i, 0, IntUtils.toInt( _fcol - 1 ), fmb, MatrixObject.UpdateType.INPLACE_PINNED);
			lmb = lmb.leftIndexingOperations(tmpLMB, i, i, 0, IntUtils.toInt( _lcol - 1 ), lmb, MatrixObject.UpdateType.INPLACE_PINNED);
		}
		return new Tuple2<>(input._1, new Tuple2<>(fmb, lmb));
	}
}
