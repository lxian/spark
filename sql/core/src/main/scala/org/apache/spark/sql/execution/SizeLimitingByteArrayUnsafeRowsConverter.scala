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

package org.apache.spark.sql.execution

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream, DataOutputStream}

import org.apache.spark.{SparkEnv, SparkException}
import org.apache.spark.internal.Logging
import org.apache.spark.io.CompressionCodec
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.UnsafeRow
import org.apache.spark.util.Utils


// scalastyle:off
/**
  * Provides methods for converting compressed byte arrays to UnsafeRows and vice versa.
  * Additionally, can enforce a limit on the total, decoded size of all decoded UnsafeRows.
  * Enforcing the limit is controlled via a sql config and if it is turned on the encoder will
  * throw a SparkException when the limit is reached.
  */
private[spark] class SizeLimitingByteArrayUnsafeRowsConverter(
                                                               maxCollectSize: Option[Long]) extends Logging {
  private var totalUncompressedResultSize = 0L
  /**
    * Packing the UnsafeRows into byte array for faster serialization.
    * The byte arrays are in the following format:
    * [size] [bytes of UnsafeRow] [size] [bytes of UnsafeRow] ... [-1]
    *
    * UnsafeRow is highly compressible (at least 8 bytes for any column), the byte array is also
    * compressed.
    */
  def encodeUnsafeRows(
                        n: Int = -1,
                        unsafeRows: Iterator[InternalRow]): Iterator[(Long, Array[Byte])] = {
    var count = 0
    val buffer = new Array[Byte](4 << 10) // 4K
    val codec = CompressionCodec.createCodec(SparkEnv.get.conf)
    val bos = new ByteArrayOutputStream()
    val out = new DataOutputStream(codec.compressedOutputStream(bos))
    // `unsafeRows.hasNext` may produce one row and buffer it, we should only call it
    // when the limit is not hit.
    while ((n < 0 || count < n) && unsafeRows.hasNext) {
      val row = unsafeRows.next().asInstanceOf[UnsafeRow]
      ensureTotalSizeIsBelowLimit(row.getSizeInBytes)
      out.writeInt(row.getSizeInBytes)
      row.writeToStream(out, buffer)
      count += 1
    }
    out.writeInt(-1)
    out.flush()
    out.close()
    Iterator((count, bos.toByteArray))
  }

  /**
    * Decodes the byte arrays back to UnsafeRows and puts them into buffer.
    */
  def decodeUnsafeRows(nFields: Int, bytes: Array[Byte]): Iterator[InternalRow] = {
    val codec = CompressionCodec.createCodec(SparkEnv.get.conf)
    val bis = new ByteArrayInputStream(bytes)
    val ins = new DataInputStream(codec.compressedInputStream(bis))

    new Iterator[InternalRow] {
      private var sizeOfNextRow = ins.readInt()

      override def hasNext: Boolean = sizeOfNextRow >= 0

      override def next(): InternalRow = {
        ensureTotalSizeIsBelowLimit(sizeOfNextRow)
        val bs = new Array[Byte](sizeOfNextRow)
        ins.readFully(bs)
        val row = new UnsafeRow(nFields)
        row.pointTo(bs, sizeOfNextRow)
        sizeOfNextRow = ins.readInt()
        row
      }
    }
  }

  private def ensureTotalSizeIsBelowLimit(sizeOfNextRow: Int): Unit = {
    totalUncompressedResultSize += sizeOfNextRow
    maxCollectSize match {
      case Some(maxSize) => if (totalUncompressedResultSize > maxSize) {
        val msg = s"Total size of uncompressed results " +
          s"(${Utils.bytesToString(totalUncompressedResultSize)}) " +
          s"is bigger than the limit.Please reduce the amount of data " +
          s"or check `spark.sql.driver.maxCollectSize` configuration"
        logError(msg)
        throw new SparkException(msg)
      }
      case _ =>
    }
  }
}