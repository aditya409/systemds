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

package org.apache.sysml.runtime.instructions.spark;

import java.util.ArrayList;
import java.util.Iterator;

import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.function.PairFlatMapFunction;

import scala.Tuple2;

import org.apache.sysml.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysml.runtime.controlprogram.context.SparkExecutionContext;
import org.apache.sysml.runtime.instructions.cp.CPOperand;
import org.apache.sysml.runtime.instructions.spark.data.LazyIterableIterator;
import org.apache.sysml.runtime.instructions.spark.data.PartitionedBroadcast;
import org.apache.sysml.runtime.instructions.spark.utils.SparkUtils;
import org.apache.sysml.runtime.matrix.MatrixCharacteristics;
import org.apache.sysml.runtime.matrix.data.MatrixBlock;
import org.apache.sysml.runtime.matrix.data.MatrixIndexes;
import org.apache.sysml.runtime.matrix.data.OperationsOnMatrixValues;
import org.apache.sysml.runtime.matrix.mapred.IndexedMatrixValue;
import org.apache.sysml.runtime.matrix.operators.Operator;
import org.apache.sysml.utils.IntUtils;

public class MatrixAppendMSPInstruction extends AppendMSPInstruction {

	protected MatrixAppendMSPInstruction(Operator op, CPOperand in1, CPOperand in2, CPOperand offset, CPOperand out,
			boolean cbind, String opcode, String istr) {
		super(op, in1, in2, offset, out, cbind, opcode, istr);
	}

	@Override
	public void processInstruction(ExecutionContext ec) {
		// map-only append (rhs must be vector and fit in mapper mem)
		SparkExecutionContext sec = (SparkExecutionContext)ec;
		checkBinaryAppendInputCharacteristics(sec, _cbind, false, false);
		MatrixCharacteristics mc1 = sec.getMatrixCharacteristics(input1.getName());
		MatrixCharacteristics mc2 = sec.getMatrixCharacteristics(input2.getName());
		int brlen = mc1.getRowsPerBlock();
		int bclen = mc1.getColsPerBlock();
		
		JavaPairRDD<MatrixIndexes,MatrixBlock> in1 = sec.getBinaryBlockRDDHandleForVariable( input1.getName() );
		PartitionedBroadcast<MatrixBlock> in2 = sec.getBroadcastForVariable( input2.getName() );
		long off = sec.getScalarInput( _offset.getName(), _offset.getValueType(), _offset.isLiteral()).getLongValue();
		
		//execute map-append operations (partitioning preserving if #in-blocks = #out-blocks)
		JavaPairRDD<MatrixIndexes,MatrixBlock> out = null;
		if( preservesPartitioning(mc1, mc2, _cbind) ) {
			out = in1.mapPartitionsToPair(
					new MapSideAppendPartitionFunction(in2, _cbind, off, brlen, bclen), true);
		}
		else {
			out = in1.flatMapToPair(
					new MapSideAppendFunction(in2, _cbind, off, brlen, bclen));
		}
		
		//put output RDD handle into symbol table
		updateBinaryAppendOutputMatrixCharacteristics(sec, _cbind);
		sec.setRDDHandleForVariable(output.getName(), out);
		sec.addLineageRDD(output.getName(), input1.getName());
		sec.addLineageBroadcast(output.getName(), input2.getName());
	}

	private static boolean preservesPartitioning( MatrixCharacteristics mcIn1, MatrixCharacteristics mcIn2, boolean cbind )
	{
		//determine if append is partitioning-preserving based on number of input and output blocks
		//with awareness of zero number of rows or columns
		long ncblksIn1 = cbind ? mcIn1.getNumColBlocks() : mcIn1.getNumRowBlocks();
		long ncblksOut = cbind ? 
				Math.max((long)Math.ceil(((double)mcIn1.getCols()+mcIn2.getCols())/mcIn1.getColsPerBlock()),1) : 
				Math.max((long)Math.ceil(((double)mcIn1.getRows()+mcIn2.getRows())/mcIn1.getRowsPerBlock()),1);
		
		//mappend is partitioning-preserving if in-block append (e.g., common case of colvector append)
		return (ncblksIn1 == ncblksOut);
	}

	private static class MapSideAppendFunction implements  PairFlatMapFunction<Tuple2<MatrixIndexes,MatrixBlock>, MatrixIndexes, MatrixBlock> 
	{
		private static final long serialVersionUID = 2738541014432173450L;
		
		private final PartitionedBroadcast<MatrixBlock> _pm;
		private final boolean _cbind;
		private final int _brlen; 
		private final int _bclen;
		private final long _lastBlockColIndex;
		
		public MapSideAppendFunction(PartitionedBroadcast<MatrixBlock> binput, boolean cbind, long offset, int brlen, int bclen)  
		{
			_pm = binput;
			_cbind = cbind;
			_brlen = brlen;
			_bclen = bclen;
			
			//check for boundary block
			_lastBlockColIndex = Math.max((long)Math.ceil(
				(double)offset/(cbind ? bclen : brlen)),1);
		}
		
		@Override
		public Iterator<Tuple2<MatrixIndexes, MatrixBlock>> call(Tuple2<MatrixIndexes, MatrixBlock> kv) 
			throws Exception 
		{
			ArrayList<Tuple2<MatrixIndexes, MatrixBlock>> ret = new ArrayList<>();
			
			IndexedMatrixValue in1 = SparkUtils.toIndexedMatrixBlock(kv);
			MatrixIndexes ix = in1.getIndexes();
			
			//case 1: pass through of non-boundary blocks
			if( (_cbind?ix.getColumnIndex():ix.getRowIndex())!=_lastBlockColIndex ) 
			{
				ret.add( kv );
			}
			//case 2: pass through full input block and rhs block 
			else if( _cbind && in1.getValue().getNumColumns() == _bclen 
					|| !_cbind && in1.getValue().getNumRows() == _brlen) 
			{				
				//output lhs block
				ret.add( kv );
				
				//output shallow copy of rhs block
				if( _cbind ) {
					ret.add( new Tuple2<>(new MatrixIndexes(ix.getRowIndex(), ix.getColumnIndex()+1),
						_pm.getBlock(IntUtils.toInt(ix.getRowIndex()), 1)) );
				}
				else { //rbind
					ret.add( new Tuple2<>(new MatrixIndexes(ix.getRowIndex()+1, ix.getColumnIndex()),
						_pm.getBlock(1, IntUtils.toInt(ix.getColumnIndex()))) );
				}
			}
			//case 3: append operation on boundary block
			else 
			{
				//allocate space for the output value
				ArrayList<IndexedMatrixValue> outlist=new ArrayList<>(2);
				IndexedMatrixValue first = new IndexedMatrixValue(new MatrixIndexes(ix), new MatrixBlock());
				outlist.add(first);
				
				MatrixBlock value_in2 = null;
				if( _cbind ) {
					value_in2 = _pm.getBlock(IntUtils.toInt(ix.getRowIndex()), 1);
					if(in1.getValue().getNumColumns()+value_in2.getNumColumns()>_bclen) {
						IndexedMatrixValue second=new IndexedMatrixValue(new MatrixIndexes(), new MatrixBlock());
						second.getIndexes().setIndexes(ix.getRowIndex(), ix.getColumnIndex()+1);
						outlist.add(second);
					}
				}
				else { //rbind
					value_in2 = _pm.getBlock(1, IntUtils.toInt(ix.getColumnIndex()));
					if(in1.getValue().getNumRows()+value_in2.getNumRows()>_brlen) {
						IndexedMatrixValue second=new IndexedMatrixValue(new MatrixIndexes(), new MatrixBlock());
						second.getIndexes().setIndexes(ix.getRowIndex()+1, ix.getColumnIndex());
						outlist.add(second);
					}
				}
	
				OperationsOnMatrixValues.performAppend(in1.getValue(), value_in2, outlist, _brlen, _bclen, _cbind, true, 0);	
				ret.addAll(SparkUtils.fromIndexedMatrixBlock(outlist));
			}
			
			return ret.iterator();
		}
	}

	private static class MapSideAppendPartitionFunction implements  PairFlatMapFunction<Iterator<Tuple2<MatrixIndexes,MatrixBlock>>, MatrixIndexes, MatrixBlock> 
	{
		private static final long serialVersionUID = 5767240739761027220L;

		private PartitionedBroadcast<MatrixBlock> _pm = null;
		private boolean _cbind = true;
		private long _lastBlockColIndex = -1;
		
		public MapSideAppendPartitionFunction(PartitionedBroadcast<MatrixBlock> binput, boolean cbind, long offset, int brlen, int bclen)  
		{
			_pm = binput;
			_cbind = cbind;
			
			//check for boundary block
			_lastBlockColIndex = Math.max((long)Math.ceil(
				(double)offset/(cbind ? bclen : brlen)),1);
		}

		@Override
		public LazyIterableIterator<Tuple2<MatrixIndexes, MatrixBlock>> call(Iterator<Tuple2<MatrixIndexes, MatrixBlock>> arg0)
			throws Exception 
		{
			return new MapAppendPartitionIterator(arg0);
		}
		
		/**
		 * Lazy mappend iterator to prevent materialization of entire partition output in-memory.
		 * The implementation via mapPartitions is required to preserve partitioning information,
		 * which is important for performance. 
		 */
		private class MapAppendPartitionIterator extends LazyIterableIterator<Tuple2<MatrixIndexes, MatrixBlock>>
		{
			public MapAppendPartitionIterator(Iterator<Tuple2<MatrixIndexes, MatrixBlock>> in) {
				super(in);
			}

			@Override
			protected Tuple2<MatrixIndexes, MatrixBlock> computeNext(Tuple2<MatrixIndexes, MatrixBlock> arg)
				throws Exception
			{
				MatrixIndexes ix = arg._1();
				MatrixBlock in1 = arg._2();
				
				//case 1: pass through of non-boundary blocks
				if( (_cbind?ix.getColumnIndex():ix.getRowIndex()) != _lastBlockColIndex ) {
					return arg;
				}
				//case 3: append operation on boundary block
				else {
					int rowix = _cbind ? IntUtils.toInt(ix.getRowIndex()) : 1;
					int colix = _cbind ? 1 : IntUtils.toInt(ix.getColumnIndex());
					MatrixBlock in2 = _pm.getBlock(rowix, colix);
					MatrixBlock out = in1.append(in2, new MatrixBlock(), _cbind);
					return new Tuple2<>(ix, out);
				}
			}
		}
	}
}
