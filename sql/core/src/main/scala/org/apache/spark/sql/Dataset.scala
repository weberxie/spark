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

package org.apache.spark.sql

import scala.collection.JavaConverters._

import org.apache.spark.annotation.Experimental
import org.apache.spark.rdd.RDD
import org.apache.spark.api.java.function._

import org.apache.spark.sql.catalyst.encoders._
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.analysis.UnresolvedAlias
import org.apache.spark.sql.catalyst.plans.Inner
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.execution.{Queryable, QueryExecution}
import org.apache.spark.sql.types.StructType

/**
 * A [[Dataset]] is a strongly typed collection of objects that can be transformed in parallel
 * using functional or relational operations.
 *
 * A [[Dataset]] differs from an [[RDD]] in the following ways:
 *  - Internally, a [[Dataset]] is represented by a Catalyst logical plan and the data is stored
 *    in the encoded form.  This representation allows for additional logical operations and
 *    enables many operations (sorting, shuffling, etc.) to be performed without deserializing to
 *    an object.
 *  - The creation of a [[Dataset]] requires the presence of an explicit [[Encoder]] that can be
 *    used to serialize the object into a binary format.  Encoders are also capable of mapping the
 *    schema of a given object to the Spark SQL type system.  In contrast, RDDs rely on runtime
 *    reflection based serialization. Operations that change the type of object stored in the
 *    dataset also need an encoder for the new type.
 *
 * A [[Dataset]] can be thought of as a specialized DataFrame, where the elements map to a specific
 * JVM object type, instead of to a generic [[Row]] container. A DataFrame can be transformed into
 * specific Dataset by calling `df.as[ElementType]`.  Similarly you can transform a strongly-typed
 * [[Dataset]] to a generic DataFrame by calling `ds.toDF()`.
 *
 * COMPATIBILITY NOTE: Long term we plan to make [[DataFrame]] extend `Dataset[Row]`.  However,
 * making this change to the class hierarchy would break the function signatures for the existing
 * functional operations (map, flatMap, etc).  As such, this class should be considered a preview
 * of the final API.  Changes will be made to the interface after Spark 1.6.
 *
 * @since 1.6.0
 */
@Experimental
class Dataset[T] private[sql](
    @transient val sqlContext: SQLContext,
    @transient val queryExecution: QueryExecution,
    tEncoder: Encoder[T]) extends Queryable with Serializable {

  /**
   * An unresolved version of the internal encoder for the type of this dataset.  This one is marked
   * implicit so that we can use it when constructing new [[Dataset]] objects that have the same
   * object type (that will be possibly resolved to a different schema).
   */
  private implicit val unresolvedTEncoder: ExpressionEncoder[T] = encoderFor(tEncoder)

  /** The encoder for this [[Dataset]] that has been resolved to its output schema. */
  private[sql] val resolvedTEncoder: ExpressionEncoder[T] =
    unresolvedTEncoder.resolve(queryExecution.analyzed.output)

  private implicit def classTag = resolvedTEncoder.clsTag

  private[sql] def this(sqlContext: SQLContext, plan: LogicalPlan)(implicit encoder: Encoder[T]) =
    this(sqlContext, new QueryExecution(sqlContext, plan), encoder)

  /**
   * Returns the schema of the encoded form of the objects in this [[Dataset]].
   *
   * @since 1.6.0
   */
  def schema: StructType = resolvedTEncoder.schema

  /* ************* *
   *  Conversions  *
   * ************* */

  /**
   * Returns a new `Dataset` where each record has been mapped on to the specified type.  The
   * method used to map columns depend on the type of `U`:
   *  - When `U` is a class, fields for the class will be mapped to columns of the same name
   *    (case sensitivity is determined by `spark.sql.caseSensitive`)
   *  - When `U` is a tuple, the columns will be be mapped by ordinal (i.e. the first column will
   *    be assigned to `_1`).
   *  - When `U` is a primitive type (i.e. String, Int, etc). then the first column of the
   *    [[DataFrame]] will be used.
   *
   * If the schema of the [[DataFrame]] does not match the desired `U` type, you can use `select`
   * along with `alias` or `as` to rearrange or rename as required.
   * @since 1.6.0
   */
  def as[U : Encoder]: Dataset[U] = {
    new Dataset(sqlContext, queryExecution, encoderFor[U])
  }

  /**
   * Applies a logical alias to this [[Dataset]] that can be used to disambiguate columns that have
   * the same name after two Datasets have been joined.
   * @since 1.6.0
   */
  def as(alias: String): Dataset[T] = withPlan(Subquery(alias, _))

  /**
   * Converts this strongly typed collection of data to generic Dataframe.  In contrast to the
   * strongly typed objects that Dataset operations work on, a Dataframe returns generic [[Row]]
   * objects that allow fields to be accessed by ordinal or name.
   */
  // This is declared with parentheses to prevent the Scala compiler from treating
  // `ds.toDF("1")` as invoking this toDF and then apply on the returned DataFrame.
  def toDF(): DataFrame = DataFrame(sqlContext, logicalPlan)

  /**
   * Returns this Dataset.
   * @since 1.6.0
   */
  // This is declared with parentheses to prevent the Scala compiler from treating
  // `ds.toDS("1")` as invoking this toDF and then apply on the returned Dataset.
  def toDS(): Dataset[T] = this

  /**
   * Converts this Dataset to an RDD.
   * @since 1.6.0
   */
  def rdd: RDD[T] = {
    val tEnc = resolvedTEncoder
    val input = queryExecution.analyzed.output
    queryExecution.toRdd.mapPartitions { iter =>
      val bound = tEnc.bind(input)
      iter.map(bound.fromRow)
    }
  }

  /* *********************** *
   *  Functional Operations  *
   * *********************** */

  /**
   * Concise syntax for chaining custom transformations.
   * {{{
   *   def featurize(ds: Dataset[T]) = ...
   *
   *   dataset
   *     .transform(featurize)
   *     .transform(...)
   * }}}
   *
   * @since 1.6.0
   */
  def transform[U](t: Dataset[T] => Dataset[U]): Dataset[U] = t(this)

  /**
   * (Scala-specific)
   * Returns a new [[Dataset]] that only contains elements where `func` returns `true`.
   * @since 1.6.0
   */
  def filter(func: T => Boolean): Dataset[T] = mapPartitions(_.filter(func))

  /**
   * (Java-specific)
   * Returns a new [[Dataset]] that only contains elements where `func` returns `true`.
   * @since 1.6.0
   */
  def filter(func: FilterFunction[T]): Dataset[T] = filter(t => func.call(t))

  /**
   * (Scala-specific)
   * Returns a new [[Dataset]] that contains the result of applying `func` to each element.
   * @since 1.6.0
   */
  def map[U : Encoder](func: T => U): Dataset[U] = mapPartitions(_.map(func))

  /**
   * (Java-specific)
   * Returns a new [[Dataset]] that contains the result of applying `func` to each element.
   * @since 1.6.0
   */
  def map[U](func: MapFunction[T, U], encoder: Encoder[U]): Dataset[U] =
    map(t => func.call(t))(encoder)

  /**
   * (Scala-specific)
   * Returns a new [[Dataset]] that contains the result of applying `func` to each element.
   * @since 1.6.0
   */
  def mapPartitions[U : Encoder](func: Iterator[T] => Iterator[U]): Dataset[U] = {
    encoderFor[T].assertUnresolved()
    new Dataset[U](
      sqlContext,
      MapPartitions[T, U](
        func,
        resolvedTEncoder,
        encoderFor[U],
        encoderFor[U].schema.toAttributes,
        logicalPlan))
  }

  /**
   * (Java-specific)
   * Returns a new [[Dataset]] that contains the result of applying `func` to each element.
   * @since 1.6.0
   */
  def mapPartitions[U](f: MapPartitionsFunction[T, U], encoder: Encoder[U]): Dataset[U] = {
    val func: (Iterator[T]) => Iterator[U] = x => f.call(x.asJava).iterator.asScala
    mapPartitions(func)(encoder)
  }

  /**
   * (Scala-specific)
   * Returns a new [[Dataset]] by first applying a function to all elements of this [[Dataset]],
   * and then flattening the results.
   * @since 1.6.0
   */
  def flatMap[U : Encoder](func: T => TraversableOnce[U]): Dataset[U] =
    mapPartitions(_.flatMap(func))

  /**
   * (Java-specific)
   * Returns a new [[Dataset]] by first applying a function to all elements of this [[Dataset]],
   * and then flattening the results.
   * @since 1.6.0
   */
  def flatMap[U](f: FlatMapFunction[T, U], encoder: Encoder[U]): Dataset[U] = {
    val func: (T) => Iterable[U] = x => f.call(x).asScala
    flatMap(func)(encoder)
  }

  /* ************** *
   *  Side effects  *
   * ************** */

  /**
   * (Scala-specific)
   * Runs `func` on each element of this Dataset.
   * @since 1.6.0
   */
  def foreach(func: T => Unit): Unit = rdd.foreach(func)

  /**
   * (Java-specific)
   * Runs `func` on each element of this Dataset.
   * @since 1.6.0
   */
  def foreach(func: ForeachFunction[T]): Unit = foreach(func.call(_))

  /**
   * (Scala-specific)
   * Runs `func` on each partition of this Dataset.
   * @since 1.6.0
   */
  def foreachPartition(func: Iterator[T] => Unit): Unit = rdd.foreachPartition(func)

  /**
   * (Java-specific)
   * Runs `func` on each partition of this Dataset.
   * @since 1.6.0
   */
  def foreachPartition(func: ForeachPartitionFunction[T]): Unit =
    foreachPartition(it => func.call(it.asJava))

  /* ************* *
   *  Aggregation  *
   * ************* */

  /**
   * (Scala-specific)
   * Reduces the elements of this Dataset using the specified binary function.  The given function
   * must be commutative and associative or the result may be non-deterministic.
   * @since 1.6.0
   */
  def reduce(func: (T, T) => T): T = rdd.reduce(func)

  /**
   * (Java-specific)
   * Reduces the elements of this Dataset using the specified binary function.  The given function
   * must be commutative and associative or the result may be non-deterministic.
   * @since 1.6.0
   */
  def reduce(func: ReduceFunction[T]): T = reduce(func.call(_, _))

  /**
   * (Scala-specific)
   * Returns a [[GroupedDataset]] where the data is grouped by the given key function.
   * @since 1.6.0
   */
  def groupBy[K : Encoder](func: T => K): GroupedDataset[K, T] = {
    val inputPlan = queryExecution.analyzed
    val withGroupingKey = AppendColumns(func, resolvedTEncoder, inputPlan)
    val executed = sqlContext.executePlan(withGroupingKey)

    new GroupedDataset(
      encoderFor[K],
      encoderFor[T],
      executed,
      inputPlan.output,
      withGroupingKey.newColumns)
  }

  /**
   * Returns a [[GroupedDataset]] where the data is grouped by the given [[Column]] expressions.
   * @since 1.6.0
   */
  @scala.annotation.varargs
  def groupBy(cols: Column*): GroupedDataset[Row, T] = {
    val withKeyColumns = logicalPlan.output ++ cols.map(_.expr).map(UnresolvedAlias)
    val withKey = Project(withKeyColumns, logicalPlan)
    val executed = sqlContext.executePlan(withKey)

    val dataAttributes = executed.analyzed.output.dropRight(cols.size)
    val keyAttributes = executed.analyzed.output.takeRight(cols.size)

    new GroupedDataset(
      RowEncoder(keyAttributes.toStructType),
      encoderFor[T],
      executed,
      dataAttributes,
      keyAttributes)
  }

  /**
   * (Java-specific)
   * Returns a [[GroupedDataset]] where the data is grouped by the given key function.
   * @since 1.6.0
   */
  def groupBy[K](f: MapFunction[T, K], encoder: Encoder[K]): GroupedDataset[K, T] =
    groupBy(f.call(_))(encoder)

  /* ****************** *
   *  Typed Relational  *
   * ****************** */

  /**
   * Selects a set of column based expressions.
   * {{{
   *   df.select($"colA", $"colB" + 1)
   * }}}
   * @since 1.6.0
   */
  // Copied from Dataframe to make sure we don't have invalid overloads.
  @scala.annotation.varargs
  protected def select(cols: Column*): DataFrame = toDF().select(cols: _*)

  /**
   * Returns a new [[Dataset]] by computing the given [[Column]] expression for each element.
   *
   * {{{
   *   val ds = Seq(1, 2, 3).toDS()
   *   val newDS = ds.select(expr("value + 1").as[Int])
   * }}}
   * @since 1.6.0
   */
  def select[U1: Encoder](c1: TypedColumn[T, U1]): Dataset[U1] = {
    new Dataset[U1](
      sqlContext,
      Project(
        c1.withInputType(
          resolvedTEncoder,
          queryExecution.analyzed.output).named :: Nil,
        logicalPlan))
  }

  /**
   * Internal helper function for building typed selects that return tuples.  For simplicity and
   * code reuse, we do this without the help of the type system and then use helper functions
   * that cast appropriately for the user facing interface.
   */
  protected def selectUntyped(columns: TypedColumn[_, _]*): Dataset[_] = {
    val encoders = columns.map(_.encoder)
    val namedColumns =
      columns.map(_.withInputType(resolvedTEncoder, queryExecution.analyzed.output).named)
    val execution = new QueryExecution(sqlContext, Project(namedColumns, logicalPlan))

    new Dataset(sqlContext, execution, ExpressionEncoder.tuple(encoders))
  }

  /**
   * Returns a new [[Dataset]] by computing the given [[Column]] expressions for each element.
   * @since 1.6.0
   */
  def select[U1, U2](c1: TypedColumn[T, U1], c2: TypedColumn[T, U2]): Dataset[(U1, U2)] =
    selectUntyped(c1, c2).asInstanceOf[Dataset[(U1, U2)]]

  /**
   * Returns a new [[Dataset]] by computing the given [[Column]] expressions for each element.
   * @since 1.6.0
   */
  def select[U1, U2, U3](
      c1: TypedColumn[T, U1],
      c2: TypedColumn[T, U2],
      c3: TypedColumn[T, U3]): Dataset[(U1, U2, U3)] =
    selectUntyped(c1, c2, c3).asInstanceOf[Dataset[(U1, U2, U3)]]

  /**
   * Returns a new [[Dataset]] by computing the given [[Column]] expressions for each element.
   * @since 1.6.0
   */
  def select[U1, U2, U3, U4](
      c1: TypedColumn[T, U1],
      c2: TypedColumn[T, U2],
      c3: TypedColumn[T, U3],
      c4: TypedColumn[T, U4]): Dataset[(U1, U2, U3, U4)] =
    selectUntyped(c1, c2, c3, c4).asInstanceOf[Dataset[(U1, U2, U3, U4)]]

  /**
   * Returns a new [[Dataset]] by computing the given [[Column]] expressions for each element.
   * @since 1.6.0
   */
  def select[U1, U2, U3, U4, U5](
      c1: TypedColumn[T, U1],
      c2: TypedColumn[T, U2],
      c3: TypedColumn[T, U3],
      c4: TypedColumn[T, U4],
      c5: TypedColumn[T, U5]): Dataset[(U1, U2, U3, U4, U5)] =
    selectUntyped(c1, c2, c3, c4, c5).asInstanceOf[Dataset[(U1, U2, U3, U4, U5)]]

  /* **************** *
   *  Set operations  *
   * **************** */

  /**
   * Returns a new [[Dataset]] that contains only the unique elements of this [[Dataset]].
   *
   * Note that, equality checking is performed directly on the encoded representation of the data
   * and thus is not affected by a custom `equals` function defined on `T`.
   * @since 1.6.0
   */
  def distinct: Dataset[T] = withPlan(Distinct)

  /**
   * Returns a new [[Dataset]] that contains only the elements of this [[Dataset]] that are also
   * present in `other`.
   *
   * Note that, equality checking is performed directly on the encoded representation of the data
   * and thus is not affected by a custom `equals` function defined on `T`.
   * @since 1.6.0
   */
  def intersect(other: Dataset[T]): Dataset[T] = withPlan[T](other)(Intersect)

  /**
   * Returns a new [[Dataset]] that contains the elements of both this and the `other` [[Dataset]]
   * combined.
   *
   * Note that, this function is not a typical set union operation, in that it does not eliminate
   * duplicate items.  As such, it is analagous to `UNION ALL` in SQL.
   * @since 1.6.0
   */
  def union(other: Dataset[T]): Dataset[T] = withPlan[T](other)(Union)

  /**
   * Returns a new [[Dataset]] where any elements present in `other` have been removed.
   *
   * Note that, equality checking is performed directly on the encoded representation of the data
   * and thus is not affected by a custom `equals` function defined on `T`.
   * @since 1.6.0
   */
  def subtract(other: Dataset[T]): Dataset[T] = withPlan[T](other)(Except)

  /* ****** *
   *  Joins *
   * ****** */

  /**
   * Joins this [[Dataset]] returning a [[Tuple2]] for each pair where `condition` evaluates to
   * true.
   *
   * This is similar to the relation `join` function with one important difference in the
   * result schema. Since `joinWith` preserves objects present on either side of the join, the
   * result schema is similarly nested into a tuple under the column names `_1` and `_2`.
   *
   * This type of join can be useful both for preserving type-safety with the original object
   * types as well as working with relational data where either side of the join has column
   * names in common.
   *
   * @since 1.6.0
   */
  def joinWith[U](other: Dataset[U], condition: Column): Dataset[(T, U)] = {
    val left = this.logicalPlan
    val right = other.logicalPlan

    val leftData = this.unresolvedTEncoder match {
      case e if e.flat => Alias(left.output.head, "_1")()
      case _ => Alias(CreateStruct(left.output), "_1")()
    }
    val rightData = other.unresolvedTEncoder match {
      case e if e.flat => Alias(right.output.head, "_2")()
      case _ => Alias(CreateStruct(right.output), "_2")()
    }


    implicit val tuple2Encoder: Encoder[(T, U)] =
      ExpressionEncoder.tuple(this.unresolvedTEncoder, other.unresolvedTEncoder)
    withPlan[(T, U)](other) { (left, right) =>
      Project(
        leftData :: rightData :: Nil,
        Join(left, right, Inner, Some(condition.expr)))
    }
  }

  /* ************************** *
   *  Gather to Driver Actions  *
   * ************************** */

  /**
   * Returns the first element in this [[Dataset]].
   * @since 1.6.0
   */
  def first(): T = rdd.first()

  /**
   * Returns an array that contains all the elements in this [[Dataset]].
   *
   * Running collect requires moving all the data into the application's driver process, and
   * doing so on a very large dataset can crash the driver process with OutOfMemoryError.
   *
   * For Java API, use [[collectAsList]].
   * @since 1.6.0
   */
  def collect(): Array[T] = rdd.collect()

  /**
   * Returns an array that contains all the elements in this [[Dataset]].
   *
   * Running collect requires moving all the data into the application's driver process, and
   * doing so on a very large dataset can crash the driver process with OutOfMemoryError.
   *
   * For Java API, use [[collectAsList]].
   * @since 1.6.0
   */
  def collectAsList(): java.util.List[T] = rdd.collect().toSeq.asJava

  /**
   * Returns the first `num` elements of this [[Dataset]] as an array.
   *
   * Running take requires moving data into the application's driver process, and doing so with
   * a very large `n` can crash the driver process with OutOfMemoryError.
   *
   * @since 1.6.0
   */
  def take(num: Int): Array[T] = rdd.take(num)

  /**
   * Returns the first `num` elements of this [[Dataset]] as an array.
   *
   * Running take requires moving data into the application's driver process, and doing so with
   * a very large `n` can crash the driver process with OutOfMemoryError.
   *
   * @since 1.6.0
   */
  def takeAsList(num: Int): java.util.List[T] = java.util.Arrays.asList(take(num) : _*)

  /* ******************** *
   *  Internal Functions  *
   * ******************** */

  private[sql] def logicalPlan = queryExecution.analyzed

  private[sql] def withPlan(f: LogicalPlan => LogicalPlan): Dataset[T] =
    new Dataset[T](sqlContext, sqlContext.executePlan(f(logicalPlan)), tEncoder)

  private[sql] def withPlan[R : Encoder](
      other: Dataset[_])(
      f: (LogicalPlan, LogicalPlan) => LogicalPlan): Dataset[R] =
    new Dataset[R](sqlContext, f(logicalPlan, other.logicalPlan))
}
