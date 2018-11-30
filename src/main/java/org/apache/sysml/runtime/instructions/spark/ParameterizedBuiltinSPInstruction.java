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
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;

import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.PairFlatMapFunction;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.broadcast.Broadcast;

import scala.Tuple2;

import org.apache.sysml.lops.Lop;
import org.apache.sysml.lops.PartialAggregate.CorrectionLocationType;
import org.apache.sysml.parser.Expression.ValueType;
import org.apache.sysml.parser.Statement;
import org.apache.sysml.runtime.DMLRuntimeException;
import org.apache.sysml.runtime.controlprogram.caching.FrameObject;
import org.apache.sysml.runtime.controlprogram.caching.MatrixObject.UpdateType;
import org.apache.sysml.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysml.runtime.controlprogram.context.SparkExecutionContext;
import org.apache.sysml.runtime.functionobjects.KahanPlus;
import org.apache.sysml.runtime.functionobjects.ParameterizedBuiltin;
import org.apache.sysml.runtime.functionobjects.ValueFunction;
import org.apache.sysml.runtime.instructions.InstructionUtils;
import org.apache.sysml.runtime.instructions.cp.CPOperand;
import org.apache.sysml.runtime.instructions.mr.GroupedAggregateInstruction;
import org.apache.sysml.runtime.instructions.spark.data.LazyIterableIterator;
import org.apache.sysml.runtime.instructions.spark.data.PartitionedBroadcast;
import org.apache.sysml.runtime.instructions.spark.functions.ExtractGroup.ExtractGroupBroadcast;
import org.apache.sysml.runtime.instructions.spark.functions.ExtractGroup.ExtractGroupJoin;
import org.apache.sysml.runtime.instructions.spark.functions.ExtractGroupNWeights;
import org.apache.sysml.runtime.instructions.spark.functions.PerformGroupByAggInCombiner;
import org.apache.sysml.runtime.instructions.spark.functions.PerformGroupByAggInReducer;
import org.apache.sysml.runtime.instructions.spark.functions.ReplicateVectorFunction;
import org.apache.sysml.runtime.instructions.spark.utils.FrameRDDConverterUtils;
import org.apache.sysml.runtime.instructions.spark.utils.RDDAggregateUtils;
import org.apache.sysml.runtime.instructions.spark.utils.SparkUtils;
import org.apache.sysml.runtime.matrix.MatrixCharacteristics;
import org.apache.sysml.runtime.matrix.data.FrameBlock;
import org.apache.sysml.runtime.matrix.data.InputInfo;
import org.apache.sysml.runtime.matrix.data.LibMatrixReorg;
import org.apache.sysml.runtime.matrix.data.MatrixBlock;
import org.apache.sysml.runtime.matrix.data.MatrixCell;
import org.apache.sysml.runtime.matrix.data.MatrixIndexes;
import org.apache.sysml.runtime.matrix.data.OperationsOnMatrixValues;
import org.apache.sysml.runtime.matrix.data.WeightedCell;
import org.apache.sysml.runtime.matrix.mapred.IndexedMatrixValue;
import org.apache.sysml.runtime.matrix.operators.AggregateOperator;
import org.apache.sysml.runtime.matrix.operators.CMOperator;
import org.apache.sysml.runtime.matrix.operators.CMOperator.AggregateOperationTypes;
import org.apache.sysml.runtime.matrix.operators.Operator;
import org.apache.sysml.runtime.matrix.operators.SimpleOperator;
import org.apache.sysml.runtime.transform.TfUtils;
import org.apache.sysml.runtime.transform.decode.Decoder;
import org.apache.sysml.runtime.transform.decode.DecoderFactory;
import org.apache.sysml.runtime.transform.encode.Encoder;
import org.apache.sysml.runtime.transform.encode.EncoderFactory;
import org.apache.sysml.runtime.transform.meta.TfMetaUtils;
import org.apache.sysml.runtime.transform.meta.TfOffsetMap;
import org.apache.sysml.runtime.util.DataConverter;
import org.apache.sysml.runtime.util.UtilFunctions;
import org.apache.sysml.utils.IntUtils;

public class ParameterizedBuiltinSPInstruction extends ComputationSPInstruction {
	protected HashMap<String, String> params;
	// removeEmpty-specific attributes
	private boolean _bRmEmptyBC = false;

	ParameterizedBuiltinSPInstruction(Operator op, HashMap<String, String> paramsMap, CPOperand out, String opcode,
			String istr, boolean bRmEmptyBC) {
		super(SPType.ParameterizedBuiltin, op, null, null, out, opcode, istr);
		params = paramsMap;
		_bRmEmptyBC = bRmEmptyBC;
	}

	public HashMap<String,String> getParams() { return params; }
	
	public static HashMap<String, String> constructParameterMap(String[] params) {
		// process all elements in "params" except first(opcode) and last(output)
		HashMap<String,String> paramMap = new HashMap<>();
		
		// all parameters are of form <name=value>
		String[] parts;
		for ( int i=1; i <= params.length-2; i++ ) {
			parts = params[i].split(Lop.NAME_VALUE_SEPARATOR);
			paramMap.put(parts[0], parts[1]);
		}
		
		return paramMap;
	}
	
	public static ParameterizedBuiltinSPInstruction parseInstruction ( String str ) {
		String[] parts = InstructionUtils.getInstructionPartsWithValueType(str);
		// first part is always the opcode
		String opcode = parts[0];

		if( opcode.equalsIgnoreCase("mapgroupedagg") )
		{
			CPOperand target = new CPOperand( parts[1] ); 
			CPOperand groups = new CPOperand( parts[2] );
			CPOperand out = new CPOperand( parts[3] );

			HashMap<String,String> paramsMap = new HashMap<>();
			paramsMap.put(Statement.GAGG_TARGET, target.getName());
			paramsMap.put(Statement.GAGG_GROUPS, groups.getName());
			paramsMap.put(Statement.GAGG_NUM_GROUPS, parts[4]);
			
			Operator op = new AggregateOperator(0, KahanPlus.getKahanPlusFnObject(), true, CorrectionLocationType.LASTCOLUMN);
			
			return new ParameterizedBuiltinSPInstruction(op, paramsMap, out, opcode, str, false);
		}
		else
		{
			// last part is always the output
			CPOperand out = new CPOperand( parts[parts.length-1] ); 

			// process remaining parts and build a hash map
			HashMap<String,String> paramsMap = constructParameterMap(parts);

			// determine the appropriate value function
			ValueFunction func = null;
			if ( opcode.equalsIgnoreCase("groupedagg")) {
				// check for mandatory arguments
				String fnStr = paramsMap.get("fn");
				if ( fnStr == null ) 
					throw new DMLRuntimeException("Function parameter is missing in groupedAggregate.");
				if ( fnStr.equalsIgnoreCase("centralmoment") ) {
					if ( paramsMap.get("order") == null )
						throw new DMLRuntimeException("Mandatory \"order\" must be specified when fn=\"centralmoment\" in groupedAggregate.");
				}
				Operator op = GroupedAggregateInstruction.parseGroupedAggOperator(fnStr, paramsMap.get("order"));
				return new ParameterizedBuiltinSPInstruction(op, paramsMap, out, opcode, str, false);
			} 
			else if (opcode.equalsIgnoreCase("rmempty")) {
				func = ParameterizedBuiltin.getParameterizedBuiltinFnObject(opcode);
				return new ParameterizedBuiltinSPInstruction(new SimpleOperator(func), paramsMap, out, opcode, str,
						parts.length > 6 ? Boolean.parseBoolean(parts[5]) : false);
			}
			else if (opcode.equalsIgnoreCase("rexpand")
				|| opcode.equalsIgnoreCase("replace")
				|| opcode.equalsIgnoreCase("lowertri")
				|| opcode.equalsIgnoreCase("uppertri")
				|| opcode.equalsIgnoreCase("transformapply")
				|| opcode.equalsIgnoreCase("transformdecode")) {
				func = ParameterizedBuiltin.getParameterizedBuiltinFnObject(opcode);
				return new ParameterizedBuiltinSPInstruction(new SimpleOperator(func), paramsMap, out, opcode, str, false);
			}
			else {
				throw new DMLRuntimeException("Unknown opcode (" + opcode + ") for ParameterizedBuiltin Instruction.");
			}
		}
	}
	
	private double getDoubleParam(ExecutionContext ec, String key) {
		String val = params.get(key);
		try {
			if(val != null)
				return Double.parseDouble( val );
			else
				throw new RuntimeException("Expected parameter " + key);
		} catch(NumberFormatException e) {
			return ec.getScalarInput(val, ValueType.DOUBLE, false).getDoubleValue();
		} 
	}
	
	private boolean getBooleanParam(ExecutionContext ec, String key) {
		String val = params.get(key);
		try {
			if(val != null)
				return Boolean.parseBoolean( val.toLowerCase() );
			else
				throw new RuntimeException("Expected parameter " + key);
		} catch(NumberFormatException e) {
			return ec.getScalarInput(val, ValueType.BOOLEAN, false).getBooleanValue();
		} 
	}
	
	private long getLongParam(ExecutionContext ec, String key) {
		String val = params.get(key);
		try {
			if(val != null)
				return Long.parseLong( val );
			else
				throw new RuntimeException("Expected parameter " + key);
		} catch(NumberFormatException e) {
			return ec.getScalarInput(val, ValueType.INT, false).getLongValue(); 
		} 
	}
	
	private long getLongParam(ExecutionContext ec, String key, long defaultValue) {
		String val = params.get(key);
		try {
			if(val != null)
				return Long.parseLong( val );
			else
				return defaultValue;
		} catch(NumberFormatException e) {
			return ec.getScalarInput(val, ValueType.INT, false).getLongValue(); 
		} 
	}
	

	@Override 
	@SuppressWarnings("unchecked")
	public void processInstruction(ExecutionContext ec) {
		SparkExecutionContext sec = (SparkExecutionContext)ec;
		String opcode = getOpcode();
		
		//opcode guaranteed to be a valid opcode (see parsing)
		if( opcode.equalsIgnoreCase("mapgroupedagg") )
		{
			//get input rdd handle
			String targetVar = params.get(Statement.GAGG_TARGET);
			String groupsVar = params.get(Statement.GAGG_GROUPS);
			JavaPairRDD<MatrixIndexes,MatrixBlock> target = sec.getBinaryBlockRDDHandleForVariable(targetVar);
			PartitionedBroadcast<MatrixBlock> groups = sec.getBroadcastForVariable(groupsVar);
			MatrixCharacteristics mc1 = sec.getMatrixCharacteristics( targetVar );
			MatrixCharacteristics mcOut = sec.getMatrixCharacteristics(output.getName());
		
			int ngroups = IntUtils.toInt(getLongParam(ec, Statement.GAGG_NUM_GROUPS));
			
			//single-block aggregation
			if( ngroups <= mc1.getRowsPerBlock() && mc1.getCols() <= mc1.getColsPerBlock() ) {
				//execute map grouped aggregate
				JavaRDD<MatrixBlock> out = target.map(new RDDMapGroupedAggFunction2(groups, _optr, ngroups));
				MatrixBlock out2 = RDDAggregateUtils.sumStable(out);
				
				//put output block into symbol table (no lineage because single block)
				//this also includes implicit maintenance of matrix characteristics
				sec.setMatrixOutput(output.getName(), out2, getExtendedOpcode());
			}
			//multi-block aggregation
			else {
				//execute map grouped aggregate
				JavaPairRDD<MatrixIndexes, MatrixBlock> out =
					target.flatMapToPair(new RDDMapGroupedAggFunction(groups, _optr,
					ngroups, mc1.getRowsPerBlock(), mc1.getColsPerBlock()));
				
				out = RDDAggregateUtils.sumByKeyStable(out, false);
				
				//updated characteristics and handle outputs
				mcOut.set(ngroups, mc1.getCols(), mc1.getRowsPerBlock(), mc1.getColsPerBlock(), -1);
				sec.setRDDHandleForVariable(output.getName(), out);
				sec.addLineageRDD( output.getName(), targetVar );
				sec.addLineageBroadcast( output.getName(), groupsVar );
			}
		}
		else if ( opcode.equalsIgnoreCase("groupedagg") )
		{
			boolean broadcastGroups = getBooleanParam(ec, "broadcast");
			
			//get input rdd handle
			String groupsVar = params.get(Statement.GAGG_GROUPS);
			JavaPairRDD<MatrixIndexes,MatrixBlock> target = sec.getBinaryBlockRDDHandleForVariable( params.get(Statement.GAGG_TARGET) );
			JavaPairRDD<MatrixIndexes,MatrixBlock> groups = broadcastGroups ? null : sec.getBinaryBlockRDDHandleForVariable( groupsVar );
			JavaPairRDD<MatrixIndexes,MatrixBlock> weights = null;
			
			MatrixCharacteristics mc1 = sec.getMatrixCharacteristics( params.get(Statement.GAGG_TARGET) );
			MatrixCharacteristics mc2 = sec.getMatrixCharacteristics( groupsVar );
			if(mc1.dimsKnown() && mc2.dimsKnown() && (mc1.getRows() != mc2.getRows() || mc2.getCols() !=1)) {
				throw new DMLRuntimeException("Grouped Aggregate dimension mismatch between target and groups.");
			}
			MatrixCharacteristics mcOut = sec.getMatrixCharacteristics(output.getName());
			
			JavaPairRDD<MatrixIndexes, WeightedCell> groupWeightedCells = null;
			
			// Step 1: First extract groupWeightedCells from group, target and weights
			if ( params.get(Statement.GAGG_WEIGHTS) != null ) {
				weights = sec.getBinaryBlockRDDHandleForVariable( params.get(Statement.GAGG_WEIGHTS) );
				
				MatrixCharacteristics mc3 = sec.getMatrixCharacteristics( params.get(Statement.GAGG_WEIGHTS) );
				if(mc1.dimsKnown() && mc3.dimsKnown() && (mc1.getRows() != mc3.getRows() || mc1.getCols() != mc3.getCols())) {
					throw new DMLRuntimeException("Grouped Aggregate dimension mismatch between target, groups, and weights.");
				}
				
				groupWeightedCells = groups.join(target).join(weights)
					.flatMapToPair(new ExtractGroupNWeights());
			}
			else //input vector or matrix
			{
				long ngroups = getLongParam(ec, Statement.GAGG_NUM_GROUPS, -1);
				
				//execute basic grouped aggregate (extract and preagg)
				if( broadcastGroups ) {
					PartitionedBroadcast<MatrixBlock> pbm = sec.getBroadcastForVariable(groupsVar);
					groupWeightedCells = target
						.flatMapToPair(new ExtractGroupBroadcast(pbm, mc1.getColsPerBlock(), ngroups, _optr));
				}
				else { //general case
					
					//replicate groups if necessary
					if( mc1.getNumColBlocks() > 1 ) {
						groups = groups.flatMapToPair(
							new ReplicateVectorFunction(false, mc1.getNumColBlocks() ));
					}
					
					groupWeightedCells = groups.join(target)
						.flatMapToPair(new ExtractGroupJoin(mc1.getColsPerBlock(), ngroups, _optr));
				}
			}
			
			// Step 2: Make sure we have brlen required while creating <MatrixIndexes, MatrixCell> 
			if(mc1.getRowsPerBlock() == -1) {
				throw new DMLRuntimeException("The block sizes are not specified for grouped aggregate");
			}
			int brlen = mc1.getRowsPerBlock();
			
			// Step 3: Now perform grouped aggregate operation (either on combiner side or reducer side)
			JavaPairRDD<MatrixIndexes, MatrixCell> out = null;
			if(_optr instanceof CMOperator && ((CMOperator) _optr).isPartialAggregateOperator() 
				|| _optr instanceof AggregateOperator ) {
				out = groupWeightedCells.reduceByKey(new PerformGroupByAggInCombiner(_optr))
					.mapValues(new CreateMatrixCell(brlen, _optr));
			}
			else {
				// Use groupby key because partial aggregation is not supported
				out = groupWeightedCells.groupByKey()
					.mapValues(new PerformGroupByAggInReducer(_optr))
					.mapValues(new CreateMatrixCell(brlen, _optr));
			}
			
			// Step 4: Set output characteristics and rdd handle 
			setOutputCharacteristicsForGroupedAgg(mc1, mcOut, out);
			
			//store output rdd handle
			sec.setRDDHandleForVariable(output.getName(), out);
			sec.addLineageRDD( output.getName(), params.get(Statement.GAGG_TARGET) );
			sec.addLineage( output.getName(), groupsVar, broadcastGroups );
			if ( params.get(Statement.GAGG_WEIGHTS) != null ) {
				sec.addLineageRDD(output.getName(), params.get(Statement.GAGG_WEIGHTS) );
			}
		}
		else if ( opcode.equalsIgnoreCase("rmempty") ) 
		{
			String rddInVar = params.get("target");
			String rddOffVar = params.get("offset");
			
			boolean rows = sec.getScalarInput(params.get("margin"), ValueType.STRING, true).getStringValue().equals("rows");
			boolean emptyReturn = getBooleanParam(ec, "empty.return");
			long maxDim = getLongParam(ec, "maxdim");
			MatrixCharacteristics mcIn = sec.getMatrixCharacteristics(rddInVar);
			
			if( maxDim > 0 ) //default case
			{
				//get input rdd handle
				JavaPairRDD<MatrixIndexes,MatrixBlock> in = sec.getBinaryBlockRDDHandleForVariable( rddInVar );
				JavaPairRDD<MatrixIndexes,MatrixBlock> off;
				PartitionedBroadcast<MatrixBlock> broadcastOff;
				long brlen = mcIn.getRowsPerBlock();
				long bclen = mcIn.getColsPerBlock();
				long numRep = (long)Math.ceil( rows ? (double)mcIn.getCols()/bclen : (double)mcIn.getRows()/brlen);
				
				//execute remove empty rows/cols operation
				JavaPairRDD<MatrixIndexes,MatrixBlock> out;
	
				if(_bRmEmptyBC){
					broadcastOff = sec.getBroadcastForVariable( rddOffVar );
					// Broadcast offset vector
					out = in
						.flatMapToPair(new RDDRemoveEmptyFunctionInMem(rows, maxDim, brlen, bclen, broadcastOff));
				}
				else {
					off = sec.getBinaryBlockRDDHandleForVariable( rddOffVar );
					out = in
						.join( off.flatMapToPair(new ReplicateVectorFunction(!rows,numRep)) )
						.flatMapToPair(new RDDRemoveEmptyFunction(rows, maxDim, brlen, bclen));
				}
	
				out = RDDAggregateUtils.mergeByKey(out, false);
				
				//store output rdd handle
				sec.setRDDHandleForVariable(output.getName(), out);
				sec.addLineageRDD(output.getName(), rddInVar);
				if(!_bRmEmptyBC)
					sec.addLineageRDD(output.getName(), rddOffVar);
				else
					sec.addLineageBroadcast(output.getName(), rddOffVar);
				
				//update output statistics (required for correctness)
				MatrixCharacteristics mcOut = sec.getMatrixCharacteristics(output.getName());
				mcOut.set(rows?maxDim:mcIn.getRows(), rows?mcIn.getCols():maxDim, IntUtils.toInt(brlen), IntUtils.toInt(bclen), mcIn.getNonZeros());
			}
			else //special case: empty output (ensure valid dims)
			{
				int n = emptyReturn ? 1 : 0;
				MatrixBlock out = new MatrixBlock(rows?n:IntUtils.toInt(mcIn.getRows()), rows?IntUtils.toInt(mcIn.getCols()):n, true); 
				sec.setMatrixOutput(output.getName(), out, getExtendedOpcode());
			}
		}
		else if ( opcode.equalsIgnoreCase("replace") ) 
		{
			JavaPairRDD<MatrixIndexes,MatrixBlock> in1 = sec.getBinaryBlockRDDHandleForVariable(params.get("target"));
			MatrixCharacteristics mcIn = sec.getMatrixCharacteristics(params.get("target"));
			
			//execute replace operation
			double pattern = getDoubleParam(ec, "pattern"); 
			double replacement = getDoubleParam(ec, "replacement");
			JavaPairRDD<MatrixIndexes,MatrixBlock> out = 
				in1.mapValues(new RDDReplaceFunction(pattern, replacement));
			
			//store output rdd handle
			sec.setRDDHandleForVariable(output.getName(), out);
			sec.addLineageRDD(output.getName(), params.get("target"));
			
			//update output statistics (required for correctness)
			MatrixCharacteristics mcOut = sec.getMatrixCharacteristics(output.getName());
			mcOut.set(mcIn.getRows(), mcIn.getCols(), mcIn.getRowsPerBlock(),
				mcIn.getColsPerBlock(), (pattern!=0 && replacement!=0)?mcIn.getNonZeros():-1);
		}
		else if ( opcode.equalsIgnoreCase("lowertri") || opcode.equalsIgnoreCase("uppertri") ) 
		{
			JavaPairRDD<MatrixIndexes,MatrixBlock> in1 = sec.getBinaryBlockRDDHandleForVariable(params.get("target"));
			MatrixCharacteristics mcIn = sec.getMatrixCharacteristics(params.get("target"));
			boolean lower = opcode.equalsIgnoreCase("lowertri");
			boolean diag = getBooleanParam(ec, "diag"); 
			boolean values = getBooleanParam(ec, "values");
			
			JavaPairRDD<MatrixIndexes,MatrixBlock> out = in1.mapPartitionsToPair(
				new RDDExtractTriangularFunction(lower, diag, values), true);
			
			//store output rdd handle
			sec.setRDDHandleForVariable(output.getName(), out);
			sec.addLineageRDD(output.getName(), params.get("target"));
			
			//update output statistics (required for correctness)
			sec.getMatrixCharacteristics(output.getName()).setDimension(mcIn.getRows(), mcIn.getCols());
		}
		else if ( opcode.equalsIgnoreCase("rexpand") ) 
		{
			String rddInVar = params.get("target");
			
			//get input rdd handle
			JavaPairRDD<MatrixIndexes,MatrixBlock> in = sec.getBinaryBlockRDDHandleForVariable( rddInVar );
			MatrixCharacteristics mcIn = sec.getMatrixCharacteristics(rddInVar);
			double maxVal = getDoubleParam(ec, "max"); 
			long lmaxVal = UtilFunctions.toLong(maxVal);
			boolean dirRows = params.get("dir").equals("rows");
			boolean cast = getBooleanParam(ec, "cast");
			boolean ignore = getBooleanParam(ec, "ignore");
			long brlen = mcIn.getRowsPerBlock();
			long bclen = mcIn.getColsPerBlock();
			
			//repartition input vector for higher degree of parallelism 
			//(avoid scenarios where few input partitions create huge outputs)
			MatrixCharacteristics mcTmp = new MatrixCharacteristics(dirRows?lmaxVal:mcIn.getRows(),
					dirRows?mcIn.getRows():lmaxVal, IntUtils.toInt(brlen), IntUtils.toInt(bclen), mcIn.getRows());
			int numParts = IntUtils.toInt(Math.min(SparkUtils.getNumPreferredPartitions(mcTmp, in), mcIn.getNumBlocks()));
			if( numParts > in.getNumPartitions()*2 )
				in = in.repartition(numParts);
			
			//execute rexpand rows/cols operation (no shuffle required because outputs are
			//block-aligned with the input, i.e., one input block generates n output blocks)
			JavaPairRDD<MatrixIndexes,MatrixBlock> out = in
				.flatMapToPair(new RDDRExpandFunction(maxVal, dirRows, cast, ignore, brlen, bclen));
			
			//store output rdd handle
			sec.setRDDHandleForVariable(output.getName(), out);
			sec.addLineageRDD(output.getName(), rddInVar);
			
			//update output statistics (required for correctness)
			MatrixCharacteristics mcOut = sec.getMatrixCharacteristics(output.getName());
			mcOut.set(dirRows?lmaxVal:mcIn.getRows(), dirRows?mcIn.getRows():lmaxVal, IntUtils.toInt(brlen), IntUtils.toInt(bclen), -1);
		}
		else if ( opcode.equalsIgnoreCase("transformapply") ) 
		{
			//get input RDD and meta data
			FrameObject fo = sec.getFrameObject(params.get("target"));
			JavaPairRDD<Long,FrameBlock> in = (JavaPairRDD<Long,FrameBlock>)
					sec.getRDDHandleForFrameObject(fo, InputInfo.BinaryBlockInputInfo);
			FrameBlock meta = sec.getFrameInput(params.get("meta"));
			MatrixCharacteristics mcIn = sec.getMatrixCharacteristics(params.get("target"));
			MatrixCharacteristics mcOut = sec.getMatrixCharacteristics(output.getName());
			String[] colnames = !TfMetaUtils.isIDSpec(params.get("spec")) ?
				in.lookup(1L).get(0).getColumnNames() : null; 
			
			//compute omit offset map for block shifts
			TfOffsetMap omap = null;
			if( TfMetaUtils.containsOmitSpec(params.get("spec"), colnames) ) {
				omap = new TfOffsetMap(SparkUtils.toIndexedLong(in.mapToPair(
					new RDDTransformApplyOffsetFunction(params.get("spec"), colnames)).collect()));
			}
			
			//create encoder broadcast (avoiding replication per task) 
			Encoder encoder = EncoderFactory.createEncoder(params.get("spec"), colnames,
				fo.getSchema(), IntUtils.toInt(fo.getNumColumns()), meta);
			mcOut.setDimension(mcIn.getRows()-((omap!=null)?omap.getNumRmRows():0), encoder.getNumCols()); 
			Broadcast<Encoder> bmeta = sec.getSparkContext().broadcast(encoder);
			Broadcast<TfOffsetMap> bomap = (omap!=null) ? sec.getSparkContext().broadcast(omap) : null;
			
			//execute transform apply
			JavaPairRDD<Long,FrameBlock> tmp = in
				.mapToPair(new RDDTransformApplyFunction(bmeta, bomap));
			JavaPairRDD<MatrixIndexes,MatrixBlock> out = FrameRDDConverterUtils
				.binaryBlockToMatrixBlock(tmp, mcOut, mcOut);
			
			//set output and maintain lineage/output characteristics
			sec.setRDDHandleForVariable(output.getName(), out);
			sec.addLineageRDD(output.getName(), params.get("target"));
			ec.releaseFrameInput(params.get("meta"));
		}
		else if ( opcode.equalsIgnoreCase("transformdecode") ) 
		{
			//get input RDD and meta data
			JavaPairRDD<MatrixIndexes,MatrixBlock> in = sec.getBinaryBlockRDDHandleForVariable(params.get("target"));
			MatrixCharacteristics mc = sec.getMatrixCharacteristics(params.get("target"));
			FrameBlock meta = sec.getFrameInput(params.get("meta"));
			String[] colnames = meta.getColumnNames();
			
			//reblock if necessary (clen > bclen)
			if( mc.getCols() > mc.getNumColBlocks() ) {
				in = in.mapToPair(new RDDTransformDecodeExpandFunction(
						IntUtils.toInt(mc.getCols()), mc.getColsPerBlock()));
				in = RDDAggregateUtils.mergeByKey(in, false);
			}
			
			//construct decoder and decode individual matrix blocks
			Decoder decoder = DecoderFactory.createDecoder(params.get("spec"), colnames, null, meta);
			JavaPairRDD<Long,FrameBlock> out = in.mapToPair(
					new RDDTransformDecodeFunction(decoder, mc.getRowsPerBlock()));
			
			//set output and maintain lineage/output characteristics
			sec.setRDDHandleForVariable(output.getName(), out);
			sec.addLineageRDD(output.getName(), params.get("target"));
			ec.releaseFrameInput(params.get("meta"));
			sec.getMatrixCharacteristics(output.getName()).set(mc.getRows(), 
				meta.getNumColumns(), mc.getRowsPerBlock(), mc.getColsPerBlock(), -1);
			sec.getFrameObject(output.getName()).setSchema(decoder.getSchema());
		}
		else {
			throw new DMLRuntimeException("Unknown parameterized builtin opcode: "+opcode);
		}
	}

	public static class RDDReplaceFunction implements Function<MatrixBlock, MatrixBlock> {
		private static final long serialVersionUID = 6576713401901671659L;
		private double _pattern; 
		private double _replacement;
		
		public RDDReplaceFunction(double pattern, double replacement) {
			_pattern = pattern;
			_replacement = replacement;
		}
		
		@Override
		public MatrixBlock call(MatrixBlock arg0) {
			return (MatrixBlock) arg0.replaceOperations(new MatrixBlock(), _pattern, _replacement);
		}
	}
	
	private static class RDDExtractTriangularFunction implements PairFlatMapFunction<Iterator<Tuple2<MatrixIndexes, MatrixBlock>>, MatrixIndexes, MatrixBlock> 
	{
		private static final long serialVersionUID = 2754868819184155702L;
		private final boolean _lower, _diag, _values;
		
		public RDDExtractTriangularFunction(boolean lower, boolean diag, boolean values) {
			_lower = lower;
			_diag = diag;
			_values = values;
		}
		
		@Override
		public LazyIterableIterator<Tuple2<MatrixIndexes, MatrixBlock>> call(Iterator<Tuple2<MatrixIndexes, MatrixBlock>> arg0) {
			return new ExtractTriangularIterator(arg0);
		}
		
		private class ExtractTriangularIterator extends LazyIterableIterator<Tuple2<MatrixIndexes, MatrixBlock>>
		{
			public ExtractTriangularIterator(Iterator<Tuple2<MatrixIndexes, MatrixBlock>> in) {
				super(in);
			}

			@Override
			protected Tuple2<MatrixIndexes, MatrixBlock> computeNext(Tuple2<MatrixIndexes, MatrixBlock> arg) {
				MatrixIndexes ix = arg._1();
				MatrixBlock mb = arg._2();
				
				//handle cases of pass-through and reset block
				if( (_lower && ix.getRowIndex() > ix.getColumnIndex())
					|| (!_lower && ix.getRowIndex() < ix.getColumnIndex()) ) {
					return _values ? arg : new Tuple2<MatrixIndexes,MatrixBlock>(
						ix, new MatrixBlock(mb.getNumRows(), mb.getNumColumns(), 1d));
				}
				
				//handle cases of empty blocks
				if( (_lower && ix.getRowIndex() < ix.getColumnIndex())
					|| (!_lower && ix.getRowIndex() > ix.getColumnIndex()) ) {
					return new Tuple2<MatrixIndexes,MatrixBlock>(ix,
						new MatrixBlock(mb.getNumRows(), mb.getNumColumns(), true));
				}
				
				//extract triangular blocks for blocks on diagonal
				assert(ix.getRowIndex() == ix.getColumnIndex());
				return new Tuple2<MatrixIndexes,MatrixBlock>(ix,
					mb.extractTriangular(new MatrixBlock(), _lower, _diag, _values));
			}
		}
	}

	public static class RDDRemoveEmptyFunction implements PairFlatMapFunction<Tuple2<MatrixIndexes,Tuple2<MatrixBlock, MatrixBlock>>,MatrixIndexes,MatrixBlock> 
	{
		private static final long serialVersionUID = 4906304771183325289L;

		private final boolean _rmRows;
		private final long _len;
		private final long _brlen;
		private final long _bclen;
		
		public RDDRemoveEmptyFunction(boolean rmRows, long len, long brlen, long bclen) {
			_rmRows = rmRows;
			_len = len;
			_brlen = brlen;
			_bclen = bclen;
		}

		@Override
		public Iterator<Tuple2<MatrixIndexes, MatrixBlock>> call(Tuple2<MatrixIndexes, Tuple2<MatrixBlock, MatrixBlock>> arg0)
			throws Exception 
		{
			//prepare inputs (for internal api compatibility)
			IndexedMatrixValue data = SparkUtils.toIndexedMatrixBlock(arg0._1(),arg0._2()._1());
			IndexedMatrixValue offsets = SparkUtils.toIndexedMatrixBlock(arg0._1(),arg0._2()._2());
			
			//execute remove empty operations
			ArrayList<IndexedMatrixValue> out = new ArrayList<>();
			LibMatrixReorg.rmempty(data, offsets, _rmRows, _len, _brlen, _bclen, out);
			
			//prepare and return outputs
			return SparkUtils.fromIndexedMatrixBlock(out).iterator();
		}
	}

	public static class RDDRemoveEmptyFunctionInMem implements PairFlatMapFunction<Tuple2<MatrixIndexes,MatrixBlock>,MatrixIndexes,MatrixBlock> 
	{
		private static final long serialVersionUID = 4906304771183325289L;

		private final boolean _rmRows;
		private final long _len;
		private final long _brlen;
		private final long _bclen;
		
		private PartitionedBroadcast<MatrixBlock> _off = null;
		
		public RDDRemoveEmptyFunctionInMem(boolean rmRows, long len, long brlen, long bclen, PartitionedBroadcast<MatrixBlock> off) 
		{
			_rmRows = rmRows;
			_len = len;
			_brlen = brlen;
			_bclen = bclen;
			_off = off;
		}

		@Override
		public Iterator<Tuple2<MatrixIndexes, MatrixBlock>> call(Tuple2<MatrixIndexes, MatrixBlock> arg0)
			throws Exception 
		{
			//prepare inputs (for internal api compatibility)
			IndexedMatrixValue data = SparkUtils.toIndexedMatrixBlock(arg0._1(),arg0._2());
			IndexedMatrixValue offsets = _rmRows ?
				SparkUtils.toIndexedMatrixBlock(arg0._1(), _off.getBlock(IntUtils.toInt(arg0._1().getRowIndex()), 1)) :
				SparkUtils.toIndexedMatrixBlock(arg0._1(), _off.getBlock(1, IntUtils.toInt(arg0._1().getColumnIndex())));
			
			//execute remove empty operations
			ArrayList<IndexedMatrixValue> out = new ArrayList<>();
			LibMatrixReorg.rmempty(data, offsets, _rmRows, _len, _brlen, _bclen, out);

			//prepare and return outputs
			return SparkUtils.fromIndexedMatrixBlock(out).iterator();
		}
	}

	public static class RDDRExpandFunction implements PairFlatMapFunction<Tuple2<MatrixIndexes,MatrixBlock>,MatrixIndexes,MatrixBlock> 
	{
		private static final long serialVersionUID = -6153643261956222601L;
		
		private double _maxVal;
		private boolean _dirRows;
		private boolean _cast;
		private boolean _ignore;
		private long _brlen;
		private long _bclen;
		
		public RDDRExpandFunction(double maxVal, boolean dirRows, boolean cast, boolean ignore, long brlen, long bclen) 
		{
			_maxVal = maxVal;
			_dirRows = dirRows;
			_cast = cast;
			_ignore = ignore;
			_brlen = brlen;
			_bclen = bclen;
		}

		@Override
		public Iterator<Tuple2<MatrixIndexes, MatrixBlock>> call(Tuple2<MatrixIndexes, MatrixBlock> arg0)
			throws Exception 
		{
			//prepare inputs (for internal api compatibility)
			IndexedMatrixValue data = SparkUtils.toIndexedMatrixBlock(arg0._1(),arg0._2());
			
			//execute rexpand operations
			ArrayList<IndexedMatrixValue> out = new ArrayList<>();
			LibMatrixReorg.rexpand(data, _maxVal, _dirRows, _cast, _ignore, _brlen, _bclen, out);
			
			//prepare and return outputs
			return SparkUtils.fromIndexedMatrixBlock(out).iterator();
		}
	}
	
	public static class RDDMapGroupedAggFunction implements PairFlatMapFunction<Tuple2<MatrixIndexes,MatrixBlock>,MatrixIndexes,MatrixBlock> 
	{
		private static final long serialVersionUID = 6795402640178679851L;
		
		private PartitionedBroadcast<MatrixBlock> _pbm = null;
		private Operator _op = null;
		private int _ngroups = -1;
		private int _brlen = -1;
		private int _bclen = -1;
		
		public RDDMapGroupedAggFunction(PartitionedBroadcast<MatrixBlock> pbm, Operator op, int ngroups, int brlen, int bclen) 
		{
			_pbm = pbm;
			_op = op;
			_ngroups = ngroups;
			_brlen = brlen;
			_bclen = bclen;
		}

		@Override
		public Iterator<Tuple2<MatrixIndexes, MatrixBlock>> call(Tuple2<MatrixIndexes, MatrixBlock> arg0)
			throws Exception 
		{
			//get all inputs
			MatrixIndexes ix = arg0._1();
			MatrixBlock target = arg0._2();		
			MatrixBlock groups = _pbm.getBlock(IntUtils.toInt(ix.getRowIndex()), 1);
			
			//execute map grouped aggregate operations
			IndexedMatrixValue in1 = SparkUtils.toIndexedMatrixBlock(ix, target);
			ArrayList<IndexedMatrixValue> outlist = new ArrayList<>();
			OperationsOnMatrixValues.performMapGroupedAggregate(_op, in1, groups, _ngroups, _brlen, _bclen, outlist);
			
			//output all result blocks
			return SparkUtils.fromIndexedMatrixBlock(outlist).iterator();
		}
	}

	/**
	 * Similar to RDDMapGroupedAggFunction but single output block.
	 */
	public static class RDDMapGroupedAggFunction2 implements Function<Tuple2<MatrixIndexes,MatrixBlock>,MatrixBlock> 
	{
		private static final long serialVersionUID = -6820599604299797661L;
		
		private PartitionedBroadcast<MatrixBlock> _pbm = null;
		private Operator _op = null;
		private int _ngroups = -1;
		
		public RDDMapGroupedAggFunction2(PartitionedBroadcast<MatrixBlock> pbm, Operator op, int ngroups) {
			_pbm = pbm;
			_op = op;
			_ngroups = ngroups;
		}

		@Override
		public MatrixBlock call(Tuple2<MatrixIndexes, MatrixBlock> arg0)
			throws Exception 
		{
			//get all inputs
			MatrixIndexes ix = arg0._1();
			MatrixBlock target = arg0._2();
			MatrixBlock groups = _pbm.getBlock(IntUtils.toInt(ix.getRowIndex()), 1);
			
			//execute map grouped aggregate operations
			return groups.groupedAggOperations(target, null, new MatrixBlock(), _ngroups, _op);
		}
	}

	public static class CreateMatrixCell implements Function<WeightedCell, MatrixCell> 
	{
		private static final long serialVersionUID = -5783727852453040737L;
		
		int brlen; Operator op;
		public CreateMatrixCell(int brlen, Operator op) {
			this.brlen = brlen;
			this.op = op;
		}

		@Override
		public MatrixCell call(WeightedCell kv) 
			throws Exception 
		{
			double val = -1;
			if(op instanceof CMOperator)
			{
				AggregateOperationTypes agg=((CMOperator)op).aggOpType;
				switch(agg)
				{
				case COUNT:
					val = kv.getWeight();
					break;
				case MEAN:
					val = kv.getValue();
					break;
				case CM2:
					val = kv.getValue()/ kv.getWeight();
					break;
				case CM3:
					val = kv.getValue()/ kv.getWeight();
					break;
				case CM4:
					val = kv.getValue()/ kv.getWeight();
					break;
				case VARIANCE:
					val = kv.getValue()/kv.getWeight();
					break;
				default:
					throw new DMLRuntimeException("Invalid aggreagte in CM_CV_Object: " + agg);
				}
			}
			else
			{
				//avoid division by 0
				val = kv.getValue()/kv.getWeight();
			}
			
			return new MatrixCell(val);
		}
	}

	public static class RDDTransformApplyFunction implements PairFunction<Tuple2<Long,FrameBlock>,Long,FrameBlock> 
	{
		private static final long serialVersionUID = 5759813006068230916L;
		
		private Broadcast<Encoder> _bencoder = null;
		private Broadcast<TfOffsetMap> _omap = null;
		
		public RDDTransformApplyFunction(Broadcast<Encoder> bencoder, Broadcast<TfOffsetMap> omap) {
			_bencoder = bencoder;
			_omap = omap;
		}

		@Override
		public Tuple2<Long,FrameBlock> call(Tuple2<Long, FrameBlock> in) 
			throws Exception 
		{
			long key = in._1();
			FrameBlock blk = in._2();
			
			//execute block transform apply
			Encoder encoder = _bencoder.getValue();
			MatrixBlock tmp = encoder.apply(blk, new MatrixBlock(blk.getNumRows(), blk.getNumColumns(), false));
			
			//remap keys
			if( _omap != null ) {
				key = _omap.getValue().getOffset(key);
			}
			
			//convert to frameblock to reuse frame-matrix reblock
			return new Tuple2<>(key, 
					DataConverter.convertToFrameBlock(tmp));
		}
	}

	public static class RDDTransformApplyOffsetFunction implements PairFunction<Tuple2<Long,FrameBlock>,Long,Long> 
	{
		private static final long serialVersionUID = 3450977356721057440L;
		
		private int[] _omitColList = null;
		
		public RDDTransformApplyOffsetFunction(String spec, String[] colnames) {
			try {
				_omitColList = TfMetaUtils.parseJsonIDList(spec, colnames, TfUtils.TXMETHOD_OMIT);
			} 
			catch (DMLRuntimeException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public Tuple2<Long,Long> call(Tuple2<Long, FrameBlock> in) 
			throws Exception 
		{
			long key = in._1();
			long rmRows = 0;
			
			FrameBlock blk = in._2();
			
			for( int i=0; i<blk.getNumRows(); i++ ) {
				boolean valid = true;
				for( int j=0; j<_omitColList.length; j++ ) {
					int colID = _omitColList[j];
					Object val = blk.get(i, colID-1);
					valid &= !(val==null || (blk.getSchema()[colID-1]==
							ValueType.STRING &&  val.toString().isEmpty())); 
				}
				rmRows += valid ? 0 : 1;
			}
			
			return new Tuple2<>(key, rmRows);
		}
	}

	public static class RDDTransformDecodeFunction implements PairFunction<Tuple2<MatrixIndexes,MatrixBlock>,Long,FrameBlock> 
	{
		private static final long serialVersionUID = -4797324742568170756L;
		
		private Decoder _decoder = null;
		private int _brlen = -1;
		
		public RDDTransformDecodeFunction(Decoder decoder, int brlen) {
			_decoder = decoder;
			_brlen = brlen;
		}

		@Override
		public Tuple2<Long,FrameBlock> call(Tuple2<MatrixIndexes, MatrixBlock> in) 
			throws Exception 
		{
			long rix = UtilFunctions.computeCellIndex(in._1().getRowIndex(), _brlen, 0);
			FrameBlock fbout = _decoder.decode(in._2(), new FrameBlock(_decoder.getSchema()));
			fbout.setColumnNames(Arrays.copyOfRange(_decoder.getColnames(), 0, fbout.getNumColumns()));
			return new Tuple2<>(rix, fbout);
		}
	}
	
	public static class RDDTransformDecodeExpandFunction implements PairFunction<Tuple2<MatrixIndexes,MatrixBlock>,MatrixIndexes,MatrixBlock> 
	{
		private static final long serialVersionUID = -8187400248076127598L;
		
		private int _clen = -1;
		private int _bclen = -1;
		
		public RDDTransformDecodeExpandFunction(int clen, int bclen) {
			_clen = clen;
			_bclen = bclen;
		}

		@Override
		public Tuple2<MatrixIndexes,MatrixBlock> call(Tuple2<MatrixIndexes, MatrixBlock> in) 
			throws Exception 
		{
			MatrixIndexes inIx = in._1();
			MatrixBlock inBlk = in._2();
			
			//construct expanded block via leftindexing
			int cl = IntUtils.toInt(UtilFunctions.computeCellIndex(inIx.getColumnIndex(), _bclen, 0)-1);
			int cu = IntUtils.toInt(UtilFunctions.computeCellIndex(inIx.getColumnIndex(), _bclen, inBlk.getNumColumns()-1)-1);
			MatrixBlock out = new MatrixBlock(inBlk.getNumRows(), _clen, false);
			out = out.leftIndexingOperations(inBlk, 0, inBlk.getNumRows()-1, cl, cu, null, UpdateType.INPLACE_PINNED);
			
			return new Tuple2<>(new MatrixIndexes(inIx.getRowIndex(), 1), out);
		}
	}

	public void setOutputCharacteristicsForGroupedAgg(MatrixCharacteristics mc1, MatrixCharacteristics mcOut, JavaPairRDD<MatrixIndexes, MatrixCell> out) {
		if(!mcOut.dimsKnown()) {
			if(!mc1.dimsKnown()) {
				throw new DMLRuntimeException("The output dimensions are not specified for grouped aggregate");
			}
			
			if ( params.get(Statement.GAGG_NUM_GROUPS) != null) {
				int ngroups = IntUtils.toInt( Double.parseDouble(params.get(Statement.GAGG_NUM_GROUPS)));
				mcOut.set(ngroups, mc1.getCols(), -1, -1); //grouped aggregate with cell output
			}
			else {
				out = SparkUtils.cacheBinaryCellRDD(out);
				mcOut.set(SparkUtils.computeMatrixCharacteristics(out));
				mcOut.setBlockSize(-1, -1); //grouped aggregate with cell output
			}
		}
	}
}
