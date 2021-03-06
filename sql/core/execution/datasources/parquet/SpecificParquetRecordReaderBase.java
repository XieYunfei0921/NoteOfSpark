/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package org.apache.spark.sql.execution.datasources.parquet;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.parquet.bytes.BytesInput;
import org.apache.parquet.bytes.BytesUtils;
import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.column.values.ValuesReader;
import org.apache.parquet.column.values.rle.RunLengthBitPackingHybridDecoder;
import org.apache.parquet.filter2.compat.FilterCompat;
import org.apache.parquet.hadoop.BadConfigurationException;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.ParquetInputFormat;
import org.apache.parquet.hadoop.ParquetInputSplit;
import org.apache.parquet.hadoop.api.InitContext;
import org.apache.parquet.hadoop.api.ReadSupport;
import org.apache.parquet.hadoop.metadata.BlockMetaData;
import org.apache.parquet.hadoop.metadata.ParquetMetadata;
import org.apache.parquet.hadoop.util.ConfigurationUtil;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.Types;
import org.apache.spark.TaskContext;
import org.apache.spark.TaskContext$;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.sql.types.StructType$;
import org.apache.spark.util.AccumulatorV2;
import scala.Option;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

import static org.apache.parquet.filter2.compat.RowGroupFilter.filterRowGroups;
import static org.apache.parquet.format.converter.ParquetMetadataConverter.NO_FILTER;
import static org.apache.parquet.format.converter.ParquetMetadataConverter.range;
import static org.apache.parquet.hadoop.ParquetFileReader.readFooter;
import static org.apache.parquet.hadoop.ParquetInputFormat.getFilter;

/**
 * 自定义记录读取器@RecordReaders 的基础类,用于parquet文件,类型为T.这个类处理行组的计算,可以对其进行过滤,和设置列读取器.
 * 这个对于parquet-mr的记录读取器来说是重量级的
 * 参数列表:
 * @file 文件路径
 * @fileSchema 文件的schema
 * @requestedSchema 请求的schema
 * @sparkSchema spark的schema
 * @totalRowCount 记录读取器最终读取器的行总数.
 * @reader parquet文件读取器
 */
public abstract class SpecificParquetRecordReaderBase<T> extends RecordReader<Void, T> {
  protected Path file;
  protected MessageType fileSchema;
  protected MessageType requestedSchema;
  protected StructType sparkSchema;
  protected long totalRowCount;

  protected ParquetFileReader reader;

  @Override
  public void initialize(InputSplit inputSplit, TaskAttemptContext taskAttemptContext)
      throws IOException, InterruptedException {
    Configuration configuration = taskAttemptContext.getConfiguration();
    ParquetInputSplit split = (ParquetInputSplit)inputSplit;
    this.file = split.getPath();
    long[] rowGroupOffsets = split.getRowGroupOffsets();

    ParquetMetadata footer;
    List<BlockMetaData> blocks;

    // if task.side.metadata is set, rowGroupOffsets is null
    if (rowGroupOffsets == null) {
      // then we need to apply the predicate push down filter
      footer = readFooter(configuration, file, range(split.getStart(), split.getEnd()));
      MessageType fileSchema = footer.getFileMetaData().getSchema();
      FilterCompat.Filter filter = getFilter(configuration);
      blocks = filterRowGroups(filter, footer.getBlocks(), fileSchema);
    } else {
      // otherwise we find the row groups that were selected on the client
      footer = readFooter(configuration, file, NO_FILTER);
      Set<Long> offsets = new HashSet<>();
      for (long offset : rowGroupOffsets) {
        offsets.add(offset);
      }
      blocks = new ArrayList<>();
      for (BlockMetaData block : footer.getBlocks()) {
        if (offsets.contains(block.getStartingPos())) {
          blocks.add(block);
        }
      }
      // verify we found them all
      if (blocks.size() != rowGroupOffsets.length) {
        long[] foundRowGroupOffsets = new long[footer.getBlocks().size()];
        for (int i = 0; i < foundRowGroupOffsets.length; i++) {
          foundRowGroupOffsets[i] = footer.getBlocks().get(i).getStartingPos();
        }
        // this should never happen.
        // provide a good error message in case there's a bug
        throw new IllegalStateException(
            "All the offsets listed in the split should be found in the file."
                + " expected: " + Arrays.toString(rowGroupOffsets)
                + " found: " + blocks
                + " out of: " + Arrays.toString(foundRowGroupOffsets)
                + " in range " + split.getStart() + ", " + split.getEnd());
      }
    }
    this.fileSchema = footer.getFileMetaData().getSchema();
    Map<String, String> fileMetadata = footer.getFileMetaData().getKeyValueMetaData();
    ReadSupport<T> readSupport = getReadSupportInstance(getReadSupportClass(configuration));
    ReadSupport.ReadContext readContext = readSupport.init(new InitContext(
        taskAttemptContext.getConfiguration(), toSetMultiMap(fileMetadata), fileSchema));
    this.requestedSchema = readContext.getRequestedSchema();
    String sparkRequestedSchemaString =
        configuration.get(ParquetReadSupport$.MODULE$.SPARK_ROW_REQUESTED_SCHEMA());
    this.sparkSchema = StructType$.MODULE$.fromString(sparkRequestedSchemaString);
    this.reader = new ParquetFileReader(
        configuration, footer.getFileMetaData(), file, blocks, requestedSchema.getColumns());
    // use the blocks from the reader in case some do not match filters and will not be read
    for (BlockMetaData block : reader.getRowGroups()) {
      this.totalRowCount += block.getRowCount();
    }

    // For test purpose.
    // If the last external accumulator is `NumRowGroupsAccumulator`, the row group number to read
    // will be updated to the accumulator. So we can check if the row groups are filtered or not
    // in test case.
    TaskContext taskContext = TaskContext$.MODULE$.get();
    if (taskContext != null) {
      Option<AccumulatorV2<?, ?>> accu = taskContext.taskMetrics().externalAccums().lastOption();
      if (accu.isDefined() && accu.get().getClass().getSimpleName().equals("NumRowGroupsAcc")) {
        @SuppressWarnings("unchecked")
        AccumulatorV2<Integer, Integer> intAccum = (AccumulatorV2<Integer, Integer>) accu.get();
        intAccum.add(blocks.size());
      }
    }
  }

  /**
   * 迭代显示指定路径@path的文件列表,mr跳过的文件会被忽略
   */
  public static List<String> listDirectory(File path) {
    List<String> result = new ArrayList<>();
    if (path.isDirectory()) {
      for (File f: path.listFiles()) {
        result.addAll(listDirectory(f));
      }
    } else {
      char c = path.getName().charAt(0);
      if (c != '.' && c != '_') {
        result.add(path.getAbsolutePath());
      }
    }
    return result;
  }

  /**
   * 初始化读取器,读取指定路径的文件使用提供的列.如果列为空,所有列都会参与
   * 这个用于测试,可以不用不使用hadoop分片机制创建读取器.
   */
  protected void initialize(String path, List<String> columns) throws IOException {
    Configuration config = new Configuration();
    config.set("spark.sql.parquet.binaryAsString", "false");
    config.set("spark.sql.parquet.int96AsTimestamp", "false");

    this.file = new Path(path);
    long length = this.file.getFileSystem(config).getFileStatus(this.file).getLen();
    ParquetMetadata footer = readFooter(config, file, range(0, length));

    List<BlockMetaData> blocks = footer.getBlocks();
    this.fileSchema = footer.getFileMetaData().getSchema();

    if (columns == null) {
      this.requestedSchema = fileSchema;
    } else {
      if (columns.size() > 0) {
        Types.MessageTypeBuilder builder = Types.buildMessage();
        for (String s: columns) {
          if (!fileSchema.containsField(s)) {
            throw new IOException("Can only project existing columns. Unknown field: " + s +
                    " File schema:\n" + fileSchema);
          }
          builder.addFields(fileSchema.getType(s));
        }
        this.requestedSchema = builder.named(ParquetSchemaConverter.SPARK_PARQUET_SCHEMA_NAME());
      } else {
        this.requestedSchema = ParquetSchemaConverter.EMPTY_MESSAGE();
      }
    }
    this.sparkSchema = new ParquetToSparkSchemaConverter(config).convert(requestedSchema);
    this.reader = new ParquetFileReader(
        config, footer.getFileMetaData(), file, blocks, requestedSchema.getColumns());
    // use the blocks from the reader in case some do not match filters and will not be read
    for (BlockMetaData block : reader.getRowGroups()) {
      this.totalRowCount += block.getRowCount();
    }
  }

  @Override
  public Void getCurrentKey() {
    return null;
  }

  // 关闭读取器
  @Override
  public void close() throws IOException {
    if (reader != null) {
      reader.close();
      reader = null;
    }
  }

  /**
   * 各种类型的迭代器,不提供操作
   */
  abstract static class IntIterator {
    abstract int nextInt() throws IOException;
  }

  protected static final class ValuesReaderIntIterator extends IntIterator {
    ValuesReader delegate;

    public ValuesReaderIntIterator(ValuesReader delegate) {
      this.delegate = delegate;
    }

    @Override
    int nextInt() {
      return delegate.readInteger();
    }
  }

  protected static final class RLEIntIterator extends IntIterator {
    RunLengthBitPackingHybridDecoder delegate;

    public RLEIntIterator(RunLengthBitPackingHybridDecoder delegate) {
      this.delegate = delegate;
    }

    @Override
    int nextInt() throws IOException {
      return delegate.readInt();
    }
  }

  protected static final class NullIntIterator extends IntIterator {
    @Override
    int nextInt() { return 0; }
  }

  /**
   * 创建一个读取器,用于定义和重复等级,如果不需要这个等级会返回一个最优值
   */
  protected static IntIterator createRLEIterator(
      int maxLevel, BytesInput bytes, ColumnDescriptor descriptor) throws IOException {
    try {
      if (maxLevel == 0) return new NullIntIterator();
      return new RLEIntIterator(
          new RunLengthBitPackingHybridDecoder(
              BytesUtils.getWidthFromMaxInt(maxLevel),
              bytes.toInputStream()));
    } catch (IOException e) {
      throw new IOException("could not read levels in page for col " + descriptor, e);
    }
  }

  private static <K, V> Map<K, Set<V>> toSetMultiMap(Map<K, V> map) {
    Map<K, Set<V>> setMultiMap = new HashMap<>();
    for (Map.Entry<K, V> entry : map.entrySet()) {
      Set<V> set = new HashSet<>();
      set.add(entry.getValue());
      setMultiMap.put(entry.getKey(), Collections.unmodifiableSet(set));
    }
    return Collections.unmodifiableMap(setMultiMap);
  }

  @SuppressWarnings("unchecked")
  private Class<? extends ReadSupport<T>> getReadSupportClass(Configuration configuration) {
    return (Class<? extends ReadSupport<T>>) ConfigurationUtil.getClassFromConfig(configuration,
        ParquetInputFormat.READ_SUPPORT_CLASS, ReadSupport.class);
  }

  /**
   * @param readSupportClass to instantiate
   * @return the configured read support
   */
  private static <T> ReadSupport<T> getReadSupportInstance(
      Class<? extends ReadSupport<T>> readSupportClass){
    try {
      return readSupportClass.getConstructor().newInstance();
    } catch (InstantiationException | IllegalAccessException |
             NoSuchMethodException | InvocationTargetException e) {
      throw new BadConfigurationException("could not instantiate read support class", e);
    }
  }
}
