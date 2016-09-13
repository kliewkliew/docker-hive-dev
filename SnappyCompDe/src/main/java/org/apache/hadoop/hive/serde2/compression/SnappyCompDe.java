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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

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
   *
   * The header contains a compressed array of data types.
   * The body contains compressed columns and their metadata.
   * The footer contains a compressed array of chunk sizes. The final four bytes of the footer encode the byte size of that compressed array.
   *
   * @param colSet
   *
   * @return ByteBuffer representing the compressed set.
   */
  @Override
  public ByteBuffer compress(ColumnBuffer[] colSet) {

    // Many compression libraries allow you to avoid allocation of intermediate arrays.
    // To use these API, we need to preallocate the output container.

    // Reserve space for the header.
    int[] dataType = new int[colSet.length];
    int maxCompressedSize = Snappy.maxCompressedLength(4*dataType.length);

    // Reserve space for the compressed nulls BitSet for each column.
    maxCompressedSize += colSet.length * Snappy.maxCompressedLength((colSet.length/8) + 1);

    // Track the length of `List<Integer> compressedSize` which will be declared later.
    int uncompressedFooterLength = 1 + 2*colSet.length;

    for (int colNum = 0; colNum < colSet.length; ++colNum) {
      // Reserve space for the compressed columns.
      dataType[colNum] = colSet[colNum].getType().toTType().getValue();
      switch (TTypeId.findByValue(dataType[colNum])) {
      case BOOLEAN_TYPE:
        maxCompressedSize += Integer.SIZE / Byte.SIZE; // This is for the encoded length.
        maxCompressedSize += Snappy.maxCompressedLength((colSet.length/8) + 1);
        break;
      case TINYINT_TYPE:
        maxCompressedSize += Snappy.maxCompressedLength(colSet.length);
        break;
      case SMALLINT_TYPE:
        maxCompressedSize += Snappy.maxCompressedLength(colSet.length * Short.SIZE / Byte.SIZE);
        break;
      case INT_TYPE:
        maxCompressedSize += Snappy.maxCompressedLength(colSet.length * Integer.SIZE / Byte.SIZE);
        break;
      case BIGINT_TYPE:
        maxCompressedSize += Snappy.maxCompressedLength(colSet.length * Long.SIZE / Byte.SIZE);
        break;
      case DOUBLE_TYPE:
        maxCompressedSize += Snappy.maxCompressedLength(colSet.length * Double.SIZE / Byte.SIZE);
        break;
      case BINARY_TYPE:
        // Reserve space for the size of the compressed array of row sizes.
        maxCompressedSize += Snappy.maxCompressedLength(colSet.length * Integer.SIZE / Byte.SIZE);

        // Reserve space for the size of the compressed flattened bytes.
        for (ByteBuffer nextBuffer : colSet[colNum].toTColumn().getBinaryVal().getValues()) {
          maxCompressedSize += Snappy.maxCompressedLength(nextBuffer.limit());
        }

        // Add an additional value to the list of compressed chunk sizes (length of `rowSize` array).
        uncompressedFooterLength++;

        break;
      case STRING_TYPE:
        // Reserve space for the size of the compressed array of row sizes.
        maxCompressedSize += Snappy.maxCompressedLength(colSet.length * Integer.SIZE / Byte.SIZE);

        // Reserve space for the size of the compressed flattened bytes.
        for (String nextString: colSet[colNum].toTColumn().getStringVal().getValues()) {
          maxCompressedSize += Snappy.maxCompressedLength(nextString.getBytes(StandardCharsets.UTF_8).length);
        }

        // Add an additional value to the list of compressed chunk sizes (length of `rowSize` array).
        uncompressedFooterLength++;

        break;
      default:
        throw new IllegalStateException("Unrecognized column type");
      }
    }
    // Reserve space for the footer.
    maxCompressedSize += Snappy.maxCompressedLength(uncompressedFooterLength * Integer.SIZE / Byte.SIZE);

    // Allocate the output container.
    ByteBuffer output = ByteBuffer.allocate(maxCompressedSize);

    // Allocate the footer. This goes in the footer because we don't know the chunk sizes until after
    // the columns have been compressed and written.
    ArrayList<Integer> compressedSize = new ArrayList<Integer>(uncompressedFooterLength);

    // Write to the output buffer.
    try {
      // Write the header.
      compressedSize.add(writePrimitives(dataType, output));

      // Write the compressed columns and metadata.
      for (int colNum = 0; colNum < colSet.length; colNum++) {
        switch (TTypeId.findByValue(dataType[colNum])) {
        case BOOLEAN_TYPE: {
          TBoolColumn column = colSet[colNum].toTColumn().getBoolVal();

          List<Boolean> bools = column.getValues();
          BitSet bsBools = new BitSet(bools.size());
          for (int rowNum = 0; rowNum < bools.size(); rowNum++) {
            bsBools.set(rowNum, bools.get(rowNum));
          }

          compressedSize.add(writePrimitives(column.getNulls(), output));

          // BitSet won't write trailing zeroes so we encode the length
          output.putInt(column.getValuesSize());

          compressedSize.add(writePrimitives(bsBools.toByteArray(), output));

          break;
        }
        case TINYINT_TYPE: {
          TByteColumn column = colSet[colNum].toTColumn().getByteVal();
          compressedSize.add(writePrimitives(column.getNulls(), output));
          compressedSize.add(writeBoxedBytes(column.getValues(), output));
          break;
        }
        case SMALLINT_TYPE: {
          TI16Column column = colSet[colNum].toTColumn().getI16Val();
          compressedSize.add(writePrimitives(column.getNulls(), output));
          compressedSize.add(writeBoxedShorts(column.getValues(), output));
          break;
        }
        case INT_TYPE: {
          TI32Column column = colSet[colNum].toTColumn().getI32Val();
          compressedSize.add(writePrimitives(column.getNulls(), output));
          compressedSize.add(writeBoxedIntegers(column.getValues(), output));
          break;
        }
        case BIGINT_TYPE: {
          TI64Column column = colSet[colNum].toTColumn().getI64Val();
          compressedSize.add(writePrimitives(column.getNulls(), output));
          compressedSize.add(writeBoxedLongs(column.getValues(), output));
          break;
        }
        case DOUBLE_TYPE: {
          TDoubleColumn column = colSet[colNum].toTColumn().getDoubleVal();
          compressedSize.add(writePrimitives(column.getNulls(), output));
          compressedSize.add(writeBoxedDoubles(column.getValues(), output));
          break;
        }
        case BINARY_TYPE: {
          TBinaryColumn column = colSet[colNum].toTColumn().getBinaryVal();

          // Initialize the array of row sizes.
          int[] rowSizes = new int[column.getValuesSize()];
          int totalSize = 0;
          for (int rowNum = 0; rowNum < column.getValuesSize(); rowNum++) {
            rowSizes[rowNum] = column.getValues().get(rowNum).limit();
            totalSize += column.getValues().get(rowNum).limit();
          }

          // Flatten the data for Snappy for a better compression ratio.
          ByteBuffer flattenedData = ByteBuffer.allocate(totalSize);
          for (int rowNum = 0; rowNum < column.getValuesSize(); rowNum++) {
            flattenedData.put(column.getValues().get(rowNum));
          }

          // Write nulls bitmap.
          compressedSize.add(writePrimitives(column.getNulls(), output));

          // Write the list of row sizes.
          compressedSize.add(writePrimitives(rowSizes, output));

          // Write the compressed, flattened data.
          compressedSize.add(writePrimitives(flattenedData.array(), output));

          break;
        }
        case STRING_TYPE: {
          TStringColumn column = colSet[colNum].toTColumn().getStringVal();

          // Initialize the array of row sizes.
          int[] rowSizes = new int[column.getValuesSize()];
          int totalSize = 0;
          for (int rowNum = 0; rowNum < column.getValuesSize(); rowNum++) {
            rowSizes[rowNum] = column.getValues().get(rowNum).length();
            totalSize += column.getValues().get(rowNum).length();
          }

          // Flatten the data for Snappy for a better compression ratio.
          StringBuilder flattenedData = new StringBuilder(totalSize);
          for (int rowNum = 0; rowNum < column.getValuesSize(); rowNum++) {
            flattenedData.append(column.getValues().get(rowNum));
          }

          // Write nulls bitmap.
          compressedSize.add(writePrimitives(column.getNulls(), output));

          // Write the list of row sizes.
          compressedSize.add(writePrimitives(rowSizes, output));

          // Write the flattened data.
          compressedSize.add(writePrimitives(flattenedData.toString().getBytes(StandardCharsets.UTF_8), output));

          break;
        }
        default:
          throw new IllegalStateException("Unrecognized column type");
        }
      }

      // Write the footer.
      output.putInt(writeBoxedIntegers(compressedSize, output));

    } catch (IOException e) {
      e.printStackTrace();
    }
    output.flip();
    return output;
  }

  /**
   * Write compressed data to the output ByteBuffer and update the position of the buffer.
   * @param boxedVals A list of boxed Java primitives.
   * @param output
   * @return The number of bytes written.
   * @throws IOException
   */
  private int writeBoxedBytes(List<Byte> boxedVals, ByteBuffer output) throws IOException {
    return writePrimitives(ArrayUtils.toPrimitive(boxedVals.toArray(new Byte[0])), output);
  }
  private int writeBoxedShorts(List<Short> boxedVals, ByteBuffer output) throws IOException {
    return writePrimitives(ArrayUtils.toPrimitive(boxedVals.toArray(new Short[0])), output);
  }
  private int writeBoxedIntegers(List<Integer> boxedVals, ByteBuffer output) throws IOException {
    return writePrimitives(ArrayUtils.toPrimitive(boxedVals.toArray(new Integer[0])), output);
  }
  private int writeBoxedLongs(List<Long> boxedVals, ByteBuffer output) throws IOException {
    return writePrimitives(ArrayUtils.toPrimitive(boxedVals.toArray(new Long[0])), output);
  }
  private int writeBoxedDoubles(List<Double> boxedVals, ByteBuffer output) throws IOException {
    return writePrimitives(ArrayUtils.toPrimitive(boxedVals.toArray(new Double[0])), output);
  }

  /**
   * Write compressed data to the output ByteBuffer and update the position of the buffer.
   * @param primitives An array of primitive data types.
   * @param output The buffer to write into.
   * @return The number of bytes written.
   * @throws IOException
   */
  private int writePrimitives(byte[] primitives, ByteBuffer output) throws IOException {
    int bytesWritten = Snappy.rawCompress(primitives, 0, primitives.length, output.array(), output.arrayOffset() + output.position());
    output.position(output.position() + bytesWritten);
    return bytesWritten;
  }
  private int writePrimitives(short[] primitives, ByteBuffer output) throws IOException {
    int bytesWritten = Snappy.rawCompress(primitives, 0, primitives.length * Short.SIZE / Byte.SIZE, output.array(), output.arrayOffset() + output.position());
    output.position(output.position() + bytesWritten);
    return bytesWritten;
  }
  private int writePrimitives(int[] primitives, ByteBuffer output) throws IOException {
    int bytesWritten = Snappy.rawCompress(primitives, 0, primitives.length * Integer.SIZE / Byte.SIZE, output.array(), output.arrayOffset() + output.position());
    output.position(output.position() + bytesWritten);
    return bytesWritten;
  }
  private int writePrimitives(long[] primitives, ByteBuffer output) throws IOException {
    int bytesWritten = Snappy.rawCompress(primitives, 0, primitives.length * Long.SIZE / Byte.SIZE, output.array(), output.arrayOffset() + output.position());
    output.position(output.position() + bytesWritten);
    return bytesWritten;
  }
  private int writePrimitives(double[] primitives, ByteBuffer output) throws IOException {
    int bytesWritten = Snappy.rawCompress(primitives, 0, primitives.length * Double.SIZE / Byte.SIZE, output.array(), output.arrayOffset() + output.position());
    output.position(output.position() + bytesWritten);
    return bytesWritten;
  }

  /**
   * Decompress a set of columns from a ByteBuffer and update the position of the buffer.
   *
   * @param input A ByteBuffer with `position` indicating the starting point of the compressed chunk.
   * @param chunkSize The length of the compressed chunk to be decompressed from the input buffer.
   *
   * @return The set of columns.
   */
  @Override
  public ColumnBuffer[] decompress(ByteBuffer input, int chunkSize) {
    int startPos = input.position();
    try {
      // Read the footer.
      int footerSize = input.getInt(startPos + chunkSize - 4);
      Iterator<Integer> compressedSize =
          Arrays.asList(ArrayUtils.toObject(
              Snappy.uncompressIntArray(
                  input.array(),
                  input.arrayOffset() + startPos + chunkSize - Integer.SIZE / Byte.SIZE - footerSize,
                  footerSize)))
          .iterator();

      // Read the header.
      int[] dataType = readIntegers(compressedSize.next(), input);
      int numOfCols = dataType.length;

      // Read the columns.
      ColumnBuffer[] outputCols = new ColumnBuffer[numOfCols];
      for (int colNum = 0; colNum < numOfCols; colNum++) {
        byte[] nulls = readBytes(compressedSize.next(), input);

        switch (TTypeId.findByValue(dataType[colNum])) {
        case BOOLEAN_TYPE: {
          int numRows = input.getInt();
          byte[] vals = readBytes(compressedSize.next(), input);
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
          byte[] vals = readBytes(compressedSize.next(), input);
          TByteColumn column = new TByteColumn(Arrays.asList(ArrayUtils.toObject(vals)), ByteBuffer.wrap(nulls));
          outputCols[colNum] = new ColumnBuffer(TColumn.byteVal(column));
          break;
        }
        case SMALLINT_TYPE: {
          short[] vals = readShorts(compressedSize.next(), input);
          TI16Column column = new TI16Column(Arrays.asList(ArrayUtils.toObject(vals)), ByteBuffer.wrap(nulls));
          outputCols[colNum] = new ColumnBuffer(TColumn.i16Val(column));
          break;
        }
        case INT_TYPE: {
          int[] vals = readIntegers(compressedSize.next(), input);
          TI32Column column = new TI32Column(Arrays.asList(ArrayUtils.toObject(vals)), ByteBuffer.wrap(nulls));
          outputCols[colNum] = new ColumnBuffer(TColumn.i32Val(column));
          break;
        }
        case BIGINT_TYPE: {
          long[] vals = readLongs(compressedSize.next(), input);
          TI64Column column = new TI64Column(Arrays.asList(ArrayUtils.toObject(vals)), ByteBuffer.wrap(nulls));
          outputCols[colNum] = new ColumnBuffer(TColumn.i64Val(column));
          break;
        }
        case DOUBLE_TYPE: {
          double[] vals = readDoubles(compressedSize.next(), input);
          TDoubleColumn column = new TDoubleColumn(Arrays.asList(ArrayUtils.toObject(vals)), ByteBuffer.wrap(nulls));
          outputCols[colNum] = new ColumnBuffer(TColumn.doubleVal(column));
          break;
        }
        case BINARY_TYPE: {
          int[] rowSize = readIntegers(compressedSize.next(), input);

          ByteBuffer flattenedData = ByteBuffer.wrap(readBytes(compressedSize.next(), input));
          ByteBuffer[] vals = new ByteBuffer[rowSize.length];

          for (int rowNum = 0; rowNum < rowSize.length; rowNum++) {
            vals[rowNum] = ByteBuffer.wrap(flattenedData.array(), flattenedData.position(), rowSize[rowNum]);
            flattenedData.position(flattenedData.position() + rowSize[rowNum]);
          }

          TBinaryColumn column = new TBinaryColumn(Arrays.asList(vals), ByteBuffer.wrap(nulls));
          outputCols[colNum] = new ColumnBuffer(TColumn.binaryVal(column));
          break;
        }
        case STRING_TYPE: {
          int[] rowSize = readIntegers(compressedSize.next(), input);

          ByteBuffer flattenedData = ByteBuffer.wrap(readBytes(compressedSize.next(), input));

          String[] vals = new String[rowSize.length];

          for (int rowNum = 0; rowNum < rowSize.length; rowNum++) {
            vals[rowNum] = new String(flattenedData.array(), flattenedData.position(), rowSize[rowNum], StandardCharsets.UTF_8);
            flattenedData.position(flattenedData.position() + rowSize[rowNum]);
          }

          TStringColumn column = new TStringColumn(Arrays.asList(vals), ByteBuffer.wrap(nulls));
          outputCols[colNum] = new ColumnBuffer(TColumn.stringVal(column));
          break;
        }
        default:
          throw new IllegalStateException("Unrecognized column type: " + TTypeId.findByValue(dataType[colNum]));
        }
      }
      input.position(startPos + chunkSize);
      return outputCols;
    } catch (IOException e) {
      e.printStackTrace();
      return (ColumnBuffer[]) null;
    }
  }

  /**
   * Read a chunk from a ByteBuffer and advance the buffer position.
   * @param chunkSize The number of bytes to decompress starting at the current position.
   * @param input The buffer to read from.
   * @return An array of primitives.
   * @throws IOException
   */
  private byte[] readBytes(int chunkSize, ByteBuffer input) throws IOException {
    byte[] vals = new byte[Snappy.uncompressedLength(input.array(), input.arrayOffset() + input.position(), chunkSize)];
    Snappy.uncompress(input.array(), input.arrayOffset() + input.position(), chunkSize, vals, 0);
    input.position(input.position() + chunkSize);
    return vals;
  }
  private short[] readShorts(int chunkSize, ByteBuffer input) throws IOException {
    short[] vals = Snappy.uncompressShortArray(input.array(), input.arrayOffset() + input.position(), chunkSize);
    input.position(input.position() + chunkSize);
    return vals;
  }
  private int[] readIntegers(int chunkSize, ByteBuffer input) throws IOException {
    int[] vals = Snappy.uncompressIntArray(input.array(), input.arrayOffset() + input.position(), chunkSize);
    input.position(input.position() + chunkSize);
    return vals;
  }
  private long[] readLongs(int chunkSize, ByteBuffer input) throws IOException {
    long[] vals = Snappy.uncompressLongArray(input.array(), input.arrayOffset() + input.position(), chunkSize);
    input.position(input.position() + chunkSize);
    return vals;
  }
  private double[] readDoubles(int chunkSize, ByteBuffer input) throws IOException {
    byte[] doubleBytes = new byte[chunkSize];
    System.arraycopy(input.array(), input.arrayOffset() + input.position(), doubleBytes, 0, chunkSize);
    double[] vals = Snappy.uncompressDoubleArray(doubleBytes);
    input.position(input.position() + chunkSize);
    return vals;
  }

  /**
   *
   * @return The plug-in name
   */
  @Override
  public String getName(){
    return "hive";
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

