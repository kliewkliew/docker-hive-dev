/**
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

package org.apache.hadoop.hive.serde2.compression;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.hadoop.hive.serde2.thrift.ColumnBuffer;
import org.apache.hive.service.rpc.thrift.*;
import org.xerial.snappy.Snappy;

public class SnappyCompDe implements CompDe {

  
  /**
   * Initialize the plug-in by overlaying the input configuration map
   * onto the plug-in's default configuration.
   * 
   * @param config Overlay configuration map
   * 
   * @return True is initialization was successful
   */
  @Override
  public boolean init(Map<String, String> config) {
    return true;
  }
  
  /**
   * Return the configuration settings of the CompDe
   * 
   * @return
   */
  @Override
  public Map<String, String> getConfig() {
    return new HashMap<String, String>();
  }
  
  /**
   * Compress a set of columns.
   * 1. write the number of columns
   * 2. for each column, write:
   *   - the data type
   *   - the size of the nulls binary
   *   - the nulls data
   *   - for string and binary rows: write the number of rows in the column followed by the size of each row
   *   - the actual data (string and binary columns are flattened)
   * 
   * @param colSet
   * 
   * @return Bytes representing the compressed set.
   */
  @Override
  public byte[] compress(ColumnBuffer[] colSet) {
    ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
    BufferedOutputStream bufferedStream = new BufferedOutputStream(bytesOut);

    try {
      bufferedStream.write(colSet.length);

      for (int colNum = 0; colNum < colSet.length; colNum++) {
        
        bufferedStream.write(colSet[colNum].getType().toTType().getValue());
        
        switch (TTypeId.findByValue(colSet[colNum].getType().toTType().getValue())) {
        case BOOLEAN_TYPE: {
          TBoolColumn column = colSet[colNum].toTColumn().getBoolVal();

          List<Boolean> bools = column.getValues();
          BitSet bsBools = new BitSet(bools.size());
          for (int rowNum = 0; rowNum < bools.size(); rowNum++) {
            bsBools.set(rowNum, bools.get(rowNum));
          }

          writePrimitives(column.getNulls(), bufferedStream);

          // BitSet won't write trailing zeroes so we encode the length
          bufferedStream.write(column.getValuesSize());

          writePrimitives(bsBools.toByteArray(), bufferedStream);

          break;
        }
        case TINYINT_TYPE: {
          TByteColumn column = colSet[colNum].toTColumn().getByteVal();
          writePrimitives(column.getNulls(), bufferedStream);
          writeBoxedBytes(column.getValues(), bufferedStream);
          break;
        }
        case SMALLINT_TYPE: {
          TI16Column column = colSet[colNum].toTColumn().getI16Val();
          writePrimitives(column.getNulls(), bufferedStream);
          writeBoxedShorts(column.getValues(), bufferedStream);
          break;
        }
        case INT_TYPE: {
          TI32Column column = colSet[colNum].toTColumn().getI32Val();
          writePrimitives(column.getNulls(), bufferedStream);
          writeBoxedIntegers(column.getValues(), bufferedStream);
          break;
        }
        case BIGINT_TYPE: {
          TI64Column column = colSet[colNum].toTColumn().getI64Val();
          writePrimitives(column.getNulls(), bufferedStream);
          writeBoxedLongs(column.getValues(), bufferedStream);
          break;
        }
        case DOUBLE_TYPE: {
          TDoubleColumn column = colSet[colNum].toTColumn().getDoubleVal();
          writePrimitives(column.getNulls(), bufferedStream);
          writeBoxedDoubles(column.getValues(), bufferedStream);
          break;
        }
        case BINARY_TYPE: {
          TBinaryColumn column = colSet[colNum].toTColumn().getBinaryVal();

          // Flatten the data for Snappy
          int[] rowSizes = new int[column.getValuesSize()]; 
          ByteArrayOutputStream flattenedData = new ByteArrayOutputStream();

          for (int rowNum = 0; rowNum < column.getValuesSize(); rowNum++) {
            byte[] row = column.getValues().get(rowNum).array();
            rowSizes[rowNum] = row.length;
            flattenedData.write(row);
          }

          // Write nulls bitmap
          writePrimitives(column.getNulls(), bufferedStream);

          // Write the list of row sizes
          writePrimitives(rowSizes, bufferedStream);

          // Write the flattened data
          writePrimitives(flattenedData.toByteArray(), bufferedStream);

          break;
        }
        case STRING_TYPE: {
          TStringColumn column = colSet[colNum].toTColumn().getStringVal();

          // Flatten the data for Snappy
          int[] rowSizes = new int[column.getValuesSize()]; 
          ByteArrayOutputStream flattenedData = new ByteArrayOutputStream();

          for (int rowNum = 0; rowNum < column.getValuesSize(); rowNum++) {
            byte[] row = column.getValues().get(rowNum).getBytes(StandardCharsets.UTF_8);
            rowSizes[rowNum] = row.length;
            flattenedData.write(row);
          }

          // Write nulls bitmap
          writePrimitives(column.getNulls(), bufferedStream);

          // Write the list of row sizes
          writePrimitives(rowSizes, bufferedStream);

          // Write the flattened data
          writePrimitives(flattenedData.toByteArray(), bufferedStream);

          break;
        }
        default:
          throw new IllegalStateException("Unrecognized column type");
        }
      }
      bufferedStream.flush();
    } catch (IOException e) {
      e.printStackTrace();
    }
    return bytesOut.toByteArray();
  }
  
  /**
   * Write the length, and data to the output stream.
   * 
   * @param boxedVals A List of boxed Java-primitives.
   * @param outputStream
   * @throws IOException 
   */
  private void writeBoxedBytes(List<Byte> boxedVals,  OutputStream outputStream) throws IOException {
    byte[] compressedVals = new byte[0];
    compressedVals = Snappy.compress(ArrayUtils.toPrimitive(boxedVals.toArray(new Byte[0])));
    writeBytes(compressedVals, outputStream);
  }
  private void writeBoxedShorts(List<Short> boxedVals,  OutputStream outputStream) throws IOException {
    byte[] compressedVals = new byte[0];
    compressedVals = Snappy.compress(ArrayUtils.toPrimitive(boxedVals.toArray(new Short[0])));
    writeBytes(compressedVals, outputStream);
  }
  private void writeBoxedIntegers(List<Integer> boxedVals,  OutputStream outputStream) throws IOException {
    byte[] compressedVals = new byte[0];
    compressedVals = Snappy.compress(ArrayUtils.toPrimitive(boxedVals.toArray(new Integer[0])));
    writeBytes(compressedVals, outputStream);
  }
  private void writeBoxedLongs(List<Long> boxedVals,  OutputStream outputStream) throws IOException {
    byte[] compressedVals = new byte[0];
    compressedVals = Snappy.compress(ArrayUtils.toPrimitive(boxedVals.toArray(new Long[0])));
    writeBytes(compressedVals, outputStream);
  }
  private void writeBoxedDoubles(List<Double> boxedVals,  OutputStream outputStream) throws IOException {
    byte[] compressedVals = new byte[0];
    compressedVals = Snappy.compress(ArrayUtils.toPrimitive(boxedVals.toArray(new Double[0])));
    writeBytes(compressedVals, outputStream);
  }

  /**
   * Write the length, and data to the output stream.
   * @param primitives
   * @param outputStream
   * @throws IOException 
   */
  private void writePrimitives(byte[] primitives, OutputStream outputStream) throws IOException {
    writeBytes(Snappy.compress(primitives), outputStream);
  }
  private void writePrimitives(int[] primitives, OutputStream outputStream) throws IOException {
    writeBytes(Snappy.compress(primitives), outputStream);
  }

  private void writeBytes(byte[] bytes, OutputStream outputStream) throws IOException {
    outputStream.write(bytes.length);
    outputStream.write(bytes);
  }

  /**
   * Decompress a set of columns
   * 
   * @param input
   * @param inputOffset
   * @param inputLength
   * 
   * @return The set of columns.
   */
  @Override
  public ColumnBuffer[] decompress(byte[] input, int inputOffset, int inputLength) {
    ByteArrayInputStream inputStream = new ByteArrayInputStream(input, inputOffset, inputLength);
    BufferedInputStream bufferedInput = new BufferedInputStream(inputStream);

    try {
      int numOfCols = bufferedInput.read();
  
      ColumnBuffer[] outputCols = new ColumnBuffer[numOfCols];
      for (int colNum = 0; colNum < numOfCols; colNum++) {
        int columnType = bufferedInput.read();
  
        byte[] nulls = Snappy.uncompress(readCompressedChunk(bufferedInput));

        switch (TTypeId.findByValue(columnType)) {
        case BOOLEAN_TYPE: {
          int numRows = bufferedInput.read();
          byte[] vals = Snappy.uncompress(readCompressedChunk(bufferedInput));
          BitSet bsBools = BitSet.valueOf(vals);

          boolean[] bools = new boolean[numRows];
          for (int rowNum = 0; rowNum < numRows; rowNum++) {
            bools[rowNum] = bsBools.get(rowNum);
          }

          TBoolColumn column = new TBoolColumn(Arrays.asList(ArrayUtils.toObject(bools)), ByteBuffer.wrap(nulls));
          outputCols[colNum] = new ColumnBuffer(TColumn.boolVal(column));
          break;
        }
        case TINYINT_TYPE: {
          byte[] vals = Snappy.uncompress(readCompressedChunk(bufferedInput));
          TByteColumn column = new TByteColumn(Arrays.asList(ArrayUtils.toObject(vals)), ByteBuffer.wrap(nulls));
          outputCols[colNum] = new ColumnBuffer(TColumn.byteVal(column));
          break;
        }
        case SMALLINT_TYPE: {
          short[] vals = Snappy.uncompressShortArray(readCompressedChunk(bufferedInput));
          TI16Column column = new TI16Column(Arrays.asList(ArrayUtils.toObject(vals)), ByteBuffer.wrap(nulls));
          outputCols[colNum] = new ColumnBuffer(TColumn.i16Val(column));
          break;
        }
        case INT_TYPE: {
          int[] vals = Snappy.uncompressIntArray(readCompressedChunk(bufferedInput));
          TI32Column column = new TI32Column(Arrays.asList(ArrayUtils.toObject(vals)), ByteBuffer.wrap(nulls));
          outputCols[colNum] = new ColumnBuffer(TColumn.i32Val(column));
          break;
        }
        case BIGINT_TYPE: {
          long[] vals = Snappy.uncompressLongArray(readCompressedChunk(bufferedInput));
          TI64Column column = new TI64Column(Arrays.asList(ArrayUtils.toObject(vals)), ByteBuffer.wrap(nulls));
          outputCols[colNum] = new ColumnBuffer(TColumn.i64Val(column));
          break;
        }
        case DOUBLE_TYPE: {
          double[] vals = Snappy.uncompressDoubleArray(readCompressedChunk(bufferedInput));
          TDoubleColumn column = new TDoubleColumn(Arrays.asList(ArrayUtils.toObject(vals)), ByteBuffer.wrap(nulls));
          outputCols[colNum] = new ColumnBuffer(TColumn.doubleVal(column));
          break;
        }
        case BINARY_TYPE: {
          int[] rowSizes = Snappy.uncompressIntArray(readCompressedChunk(bufferedInput));

          BufferedInputStream flattenedRows = new BufferedInputStream(IOUtils.toInputStream(
              Snappy.uncompressString(readCompressedChunk(bufferedInput))));

          ByteBuffer[] vals = new ByteBuffer[rowSizes.length];

          for (int rowNum = 0; rowNum < rowSizes.length; rowNum++) {
            byte[] row = new byte[rowSizes[rowNum]];
            flattenedRows.read(row, 0, rowSizes[rowNum]);
            vals[rowNum] = ByteBuffer.wrap(row);
          }

          TBinaryColumn column = new TBinaryColumn(Arrays.asList(vals), ByteBuffer.wrap(nulls));
          outputCols[colNum] = new ColumnBuffer(TColumn.binaryVal(column));
          break;
        }
        case STRING_TYPE: {
          int[] rowSizes = Snappy.uncompressIntArray(readCompressedChunk(bufferedInput));

          BufferedInputStream flattenedRows = new BufferedInputStream(IOUtils.toInputStream(
              Snappy.uncompressString(readCompressedChunk(bufferedInput))));

          String[] vals = new String[rowSizes.length];

          for (int rowNum = 0; rowNum < rowSizes.length; rowNum++) {
            byte[] row = new byte[rowSizes[rowNum]];
            flattenedRows.read(row, 0, rowSizes[rowNum]);
            vals[rowNum] = new String(row, StandardCharsets.UTF_8);
          }

          TStringColumn column = new TStringColumn(Arrays.asList(vals), ByteBuffer.wrap(nulls));
          outputCols[colNum] = new ColumnBuffer(TColumn.stringVal(column));
          break;
        }
        default:
          throw new IllegalStateException("Unrecognized column type: " + TTypeId.findByValue(columnType));
        }
      }
      return outputCols;
    } catch (IOException e) {
      e.printStackTrace();
      return (ColumnBuffer[]) null;
    }
  }

  /**
   * Read a compressed chunk from a stream. 
   * @param input
   * @return
   * @throws IOException
   */
  public byte[] readCompressedChunk(InputStream input) throws IOException {
    int compressedValsSize = input.read();
    byte[] compressedVals = new byte[compressedValsSize];
    input.read(compressedVals, 0, compressedValsSize);
    return compressedVals;
  }

  /**
   * 
   * @return The plug-in name
   */
  @Override
  public String getName(){
    return "snappy";
  }

  /**
   * Provide a namespace for the plug-in
   * 
   * @return The vendor name
   */
  @Override
  public String getVendor() {
    return "snappy";
  }

}
