/*
 * Copyright (c) 2016-2018 "Neo4j, Inc." [https://neo4j.com]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opencypher.caps.api.value

import org.opencypher.caps.api.exception.UnsupportedOperationException
import org.opencypher.caps.api.types._
import org.opencypher.caps.api.value.CypherValue._

import scala.collection.mutable
import scala.language.implicitConversions
import scala.reflect.ClassTag

case class CAPSRelationship(override val id: Long, override val source: Long, override val target: Long, override val relType: String, override val properties: MapData = Map.empty) extends CypherRelationship(id, source, target, relType, properties)

case class CAPSNode(override val id: Long, override val labels: Set[String] = Set.empty, override val properties: MapData = Map.empty) extends CypherNode[Long](id, labels, properties)

sealed trait NullableCypherEntity[+Id] extends NullableCypherValue[NullableCypherEntity[Id]]

sealed trait CypherEntity[+Id] extends NullableCypherEntity[Id] with CypherValue[CypherEntity[Id]] {
  def id: Id

  override def isOrContainsNull: Boolean = false

  def properties: MapData
}

sealed trait NullableCypherNode[+Id] extends NullableCypherEntity[Id] with NullableCypherValue[NullableCypherNode[Id]]

class CypherNode[+Id](val id: Id, val labels: Set[String] = Set.empty, val properties: MapData = Map.empty) extends NullableCypherNode[Id] with CypherValue[CypherNode[Id]] with CypherEntity[Id] with Product {
  override def cypherType: CypherType = CTNode(labels)

  override def value: CypherNode[Id] = this

  override def productElement(n: Int): Any = n match {
    case 0 => id
    case 1 => labels
    case 2 => properties
    case _ => throw UnsupportedOperationException("Invalid product access.")
  }

  override def productArity: Int = 3

  override def canEqual(that: Any): Boolean = {
    that match {
      case cn: CypherNode[_] if cn.id == id && cn.labels == labels && cn.properties == properties => true
      case _ => false
    }
  }

}

object CypherNode {

  def unapply[Id](n: CypherNode[Id]): Option[(Id, Set[String], MapData)] = {
    Some((n.id, n.labels, n.properties))
  }

}

sealed trait NullableCypherRelationship[+Id] extends NullableCypherEntity[Id] with NullableCypherValue[NullableCypherRelationship[Id]]

class CypherRelationship[+Id](val id: Id, val source: Id, val target: Id, val relType: String, val properties: MapData = Map.empty) extends NullableCypherRelationship[Id] with CypherValue[CypherRelationship[Id]] with CypherEntity[Id] with Product {
  override def cypherType: CypherType = CTRelationship(relType)

  override def value: CypherRelationship[Id] = this

  override def productElement(n: Int): Any = n match {
    case 0 => id
    case 1 => source
    case 2 => target
    case 3 => relType
    case 4 => properties
    case _ => throw UnsupportedOperationException("Invalid product access.")
  }

  override def productArity: Int = 5

  override def canEqual(that: Any): Boolean = {
    that match {
      case cr: CypherRelationship[_] if cr.id == id && cr.source == source && cr.target == target && cr.relType == relType && cr.properties == properties => true
      case _ => false
    }
  }

}

object CypherRelationship {

  def unapply[Id](r: CypherRelationship[Id]): Option[(Id, Id, Id, String, MapData)] = {
    Some((r.id, r.source, r.target, r.relType, r.properties))
  }

}

sealed trait NullableCypherValue[+V] {

  def value: V

  def javaValue: Any = value

  def getValue: Option[V] = None

  def isNull: Boolean

  private[caps] def isOrContainsNull: Boolean

  def cypherType: CypherType

  def asMaterial: Option[MaterialCypherValue] = as[MaterialCypherValue]

  def as[V <: CypherValue[_] : ClassTag]: Option[V] = this match {
    case v: V => Some(v)
    case _ => None
  }

  override def hashCode(): Int = {
    this match {
      case CypherNull => super.hashCode
      case cn: CypherNode[_] => cn.id.hashCode()
      case cr: CypherRelationship[_] => cr.id.hashCode()
      case _: MaterialCypherValue => value.hashCode
    }
  }

  override def equals(other: Any): Boolean = {
    other match {
      case CypherNull => this.isNull
      case cn: CypherNode[_] => cn.canEqual(this)
      case cr: CypherRelationship[_] => cr.canEqual(this)
      case cv: MaterialCypherValue => this.value == cv.value
      case _ => false
    }
  }

  def cypherEqual(other: NullableCypherValue[_]): Ternary = {
    if (isOrContainsNull || other.isOrContainsNull) {
      Maybe
    } else {
      other.value.equals(this.value)
    }
  }

  private[caps] def materialLessThan(other: MaterialCypherValue): Ternary = {
    throw new UnsupportedOperationException(s"Cypher entities of type ${this.cypherType} and ${other.cypherType} are not comparable.")
  }

  def cypherLessThan(other: NullableCypherValue[_]): Ternary = {
    if (this.isNull) {
      Maybe
    } else {
      other match {
        case CypherNull => Maybe
        case cv: MaterialCypherValue => materialLessThan(cv)
      }
    }
  }

  def cypherLessThanOrEqual(other: NullableCypherValue[_]): Ternary = cypherEqual(other) or cypherLessThan(other)

  def cypherLargerThan(other: NullableCypherValue[_]): Ternary = cypherLessThan(other).negated

  def cypherLargerThanOrEqual(other: NullableCypherValue[_]): Ternary = cypherEqual(other) or cypherLargerThan(other)

  override def toString(): String = {
    def toPropertyString(values: MapData): String = {
      if (values.isEmpty) "" else values.toSeq.sortBy(_._1).map { case (k: String, v: NullableCypherValue[_]) =>
        s"$k: ${v.toString}"
      }.mkString("{", ", ", "}")
    }

    this match {
      case CypherNull => "null"
      case _: CypherString => s"'$value'"
      case m: CypherMap => toPropertyString(m.value)
      case r: CypherRelationship[_] => s"[:${r.relType}${toPropertyString(r.properties)}]"
      case n: CypherNode[_] =>
        val labelString = if (n.labels.isEmpty) "" else n.labels.toSeq.sorted.mkString(":", ":", "")
        Seq(labelString, toPropertyString(n.properties)).filter(_.nonEmpty).mkString("(", " ", ")")
      case _ => value.toString
    }
  }

}

sealed trait CypherValue[+V] extends NullableCypherValue[V] {
  override def isNull = false

  // Overridden by Map and List
  override def isOrContainsNull: Boolean = false

  override def getValue = Some(value)
}

object CypherValue {

  type MaterialCypherValue = CypherValue[_]

  type CypherParameters = collection.immutable.Map[String, MaterialCypherValue]

  type MapData = collection.immutable.Map[String, NullableCypherValue[_]]

  object Properties {
    def apply(values: (String, Any)*): MapData = {
      values.map { case (k, v) => k -> CypherValue.nullable(v) }.toMap
    }

    def empty: MapData = Map.empty
  }

  // TODO: Exhaustive match
  def nullable(v: Any): NullableCypherValue[_] = {
    v match {
      case cv: NullableCypherValue[_] => cv
      case a: mutable.WrappedArray[_] => a.map(CypherValue.nullable(_)).toList
      case l: List[_] => l.map(CypherValue.nullable(_)).toList
      case ji: Integer => ji.toInt
      case b: Boolean => b
      case l: Long => l
      case i: Int => i
      case d: Double => d
      case f: Float => f
      case s: String => s
      case null => CypherNull
    }
  }

  def apply(v: Any): CypherValue[_] = {
    nullable(v) match {
      case cv: MaterialCypherValue => cv
      case other => throw new UnsupportedOperationException(s"$other is not a valid non-nullable Cypher value.")
    }
  }

  sealed trait NullableCypherList[+E <: NullableCypherValue[Any]] extends NullableCypherValue[List[E]]

  implicit class CypherList[V <: NullableCypherValue[_], P](l: List[P])(implicit ev: P => V) extends NullableCypherList[V] with CypherValue[List[V]] {
    val value: List[V] = l.map(ev.apply(_))

    override def cypherType: CypherType = CTList(value.map(v => v.cypherType).foldLeft[CypherType](CTVoid)(_ join _))

    override def isOrContainsNull: Boolean = value.contains(CypherNull)
  }

  sealed trait NullableCypherBoolean extends NullableCypherValue[Boolean]

  implicit class CypherBoolean(val value: Boolean) extends NullableCypherBoolean with CypherValue[Boolean] {
    override def cypherType: CypherType = CTBoolean
  }

  sealed trait NullableCypherNumber extends NullableCypherValue[Any]

  sealed trait CypherNumber extends NullableCypherNumber with CypherValue[Any] {
    def longOrDouble: Either[Long, Double]
  }

  sealed trait NullableCypherFloat extends NullableCypherValue[Double] with NullableCypherNumber

  implicit def floatToCypherFloat(f: Float): CypherFloat = f.toDouble

  implicit class CypherFloat(val value: Double) extends NullableCypherFloat with NullableCypherNumber with CypherNumber with CypherValue[Double] {
    override def cypherType: CypherType = CTFloat

    override def longOrDouble: Either[Long, Double] = Right(value)
  }

  sealed trait NullableCypherInteger extends NullableCypherValue[Long] with NullableCypherNumber

  implicit def intToCypherInteger(i: Int): CypherInteger = i.toLong

  implicit class CypherInteger(val value: Long) extends NullableCypherInteger with NullableCypherNumber with CypherNumber with CypherValue[Long] {
    override def cypherType: CypherType = CTInteger

    override def longOrDouble: Either[Long, Double] = Left(value)
  }

  sealed trait NullableCypherString extends NullableCypherValue[String]

  implicit class CypherString(val value: String) extends NullableCypherString with CypherValue[String] {
    override def cypherType: CypherType = CTString
  }

  sealed trait NullableCypherMap extends NullableCypherValue[MapData] {
    def keys: Set[String]

    def apply(key: String): NullableCypherValue[_]

    def get(key: String): Option[CypherValue[_]]
  }

  object CypherMap {
    def apply(values: (String, Any)*): CypherMap = {
      Properties(values: _*)
    }
  }

  implicit class CypherMap(val value: MapData) extends NullableCypherMap with CypherValue[MapData] {
    override def cypherType: CypherType = CTMap

    def keys: Set[String] = value.keySet

    override def get(key: String): Option[CypherValue[_]] = value.get(key).flatMap(_.as[CypherValue[_]])

    override def apply(key: String): NullableCypherValue[_] = value.getOrElse(key, CypherNull)

    override def isOrContainsNull: Boolean = value.valuesIterator.contains(CypherNull)
  }

  case object CypherNull
    extends NullableCypherRelationship[Nothing]
      with NullableCypherNode[Nothing]
      with NullableCypherList[Nothing]
      with NullableCypherMap
      with NullableCypherBoolean
      with NullableCypherString
      with NullableCypherInteger
      with NullableCypherFloat
      with NullableCypherValue[Nothing] {
    override def isNull = true

    override def hashCode(): Int = "null".hashCode()

    override def equals(other: Any): Boolean = {
      other match {
        case ref: AnyRef => this.eq(ref)
        case _ => false
      }
    }

    override def cypherType: CypherType = CTNull

    override def value = throw UnsupportedOperationException("CypherNull has no value.")

    override def apply(key: String): NullableCypherValue[Nothing] = this

    override def getValue: Option[Nothing] = None

    override def get(key: String): Option[CypherValue[_]] = None

    override def isOrContainsNull: Boolean = true

    override def keys: Set[String] = Set.empty
  }

}
