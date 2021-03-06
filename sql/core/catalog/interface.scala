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

package org.apache.spark.sql.catalog

import javax.annotation.Nullable

import org.apache.spark.annotation.Stable
import org.apache.spark.sql.catalyst.DefinedByConstructorParams


// Note: all classes here are expected to be wrapped in Datasets and so must extend
// DefinedByConstructorParams for the catalog to be able to create encoders for them.

/**
 spark的数据库抽象,可以有@listDatabases 返回
 *
 * @param name 数据块名称
 * @param description 数据库描述
 * @param locationUri 数据块文件位置
 */
@Stable
class Database(
    val name: String,
    @Nullable val description: String,
    val locationUri: String)
  extends DefinedByConstructorParams {

  override def toString: String = {
    "Database[" +
      s"name='$name', " +
      Option(description).map { d => s"description='$d', " }.getOrElse("") +
      s"path='$locationUri']"
  }

}


/**
spark表的抽象,由@listTables 返回
 *
 * @param name 表名称
 * @param database 表所属数据库名称
 * @param description 表的描述信息
 * @param tableType 表类型(视图/表)
 * @param isTemporary 是否为临时表
 */
@Stable
class Table(
    val name: String,
    @Nullable val database: String,
    @Nullable val description: String,
    val tableType: String,
    val isTemporary: Boolean)
  extends DefinedByConstructorParams {

  override def toString: String = {
    "Table[" +
      s"name='$name', " +
      Option(database).map { d => s"database='$d', " }.getOrElse("") +
      Option(description).map { d => s"description='$d', " }.getOrElse("") +
      s"tableType='$tableType', " +
      s"isTemporary='$isTemporary']"
  }

}


/**
 spark的列,由@listColumns 返回
 * @param name 列名称
 * @param description 列描述
 * @param dataType 列数据描述
 * @param nullable 是否列可以为空
 * @param isPartition 这个类是否为分区列
 * @param isBucket 列是否为桶列
 * @since 2.0.0
 */
@Stable
class Column(
    val name: String,
    @Nullable val description: String,
    val dataType: String,
    val nullable: Boolean,
    val isPartition: Boolean,
    val isBucket: Boolean)
  extends DefinedByConstructorParams {

  override def toString: String = {
    "Column[" +
      s"name='$name', " +
      Option(description).map { d => s"description='$d', " }.getOrElse("") +
      s"dataType='$dataType', " +
      s"nullable='$nullable', " +
      s"isPartition='$isPartition', " +
      s"isBucket='$isBucket']"
  }

}


/**
 spark中用户定义的函数,由@listFunctions 返回
 *
 * @param name 函数名称
 * @param database 函数所属的数据库
 * @param description 函数描述
 * @param className 函数的全类名
 * @param isTemporary 是否为临时函数
 */
@Stable
class Function(
    val name: String,
    @Nullable val database: String,
    @Nullable val description: String,
    val className: String,
    val isTemporary: Boolean)
  extends DefinedByConstructorParams {

  override def toString: String = {
    "Function[" +
      s"name='$name', " +
      Option(database).map { d => s"database='$d', " }.getOrElse("") +
      Option(description).map { d => s"description='$d', " }.getOrElse("") +
      s"className='$className', " +
      s"isTemporary='$isTemporary']"
  }

}
