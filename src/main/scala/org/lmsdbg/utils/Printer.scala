package org.lmsdbg
package utils

import org.scaladebugger.api.profiles.traits.info._
import Definitions._
import Localizers._

/**
 * Trait to get string representation of values
 */
trait Formatter {
  def format(value: ValueInfoProfile, depth: Int): String
  def format(value: ValueInfoProfile): String = format(value, 1)
}

/**
 * Handles primitives and objects differently
 */
trait JVMFormatter extends Formatter {
  def format(value: ValueInfoProfile, depth: Int): String = value.toPrettyString
}

/** 
 * Prints a better representation of classes and objects as Products
 * Also handles the case of boxed primitive types and objects with toStringFields
 */
trait ScalaVMFormatter extends JVMFormatter {

  /** Fields that are compiler generate and/or non useful */
  private val ignoredFields = List("hashCode", "MODULE$", "$outer", "bitmap$0")

  private def unboxIfNeeded(value: ValueInfoProfile): ValueInfoProfile = {
    if (BoxedPrimitives.contains(value.typeInfo.name)) {
      unbox(value.toObjectInfo)
    } else {
      value
    }
  }

  private def toStringFieldOrSame(value: ValueInfoProfile): ValueInfoProfile = {
    value.tryToObjectInfo
      .flatMap(_.tryField("toString"))
      .map(_.toValueInfo)
      .toOption
      .getOrElse(value)
  }

  override def format(value: ValueInfoProfile, depth: Int): String = {
    Utils.printDebug(s"Formatting value -> ${value.toPrettyString}, depth = $depth")

    val unboxed = toStringFieldOrSame(unboxIfNeeded(value))
    val typ = unboxed.typeInfo
    if (isSubtypeOfList(typ)) {
      if (typ.name == NilObjectClassName) "Nil" else "List(...)"
    } else if (depth <= 0 || typ.isPrimitiveType || typ.isStringType || typ.isNullType) {
      unboxed.toPrettyString
    } else {
      val obj = value.toObjectInfo
      val className = classPrefix(typ.name)
      val prefix = if (className.endsWith("$")) {
        s"object ${className.stripSuffix("$")}"
      } else {
        s"class $className"
      }
      val fields = obj.fields.filterNot(field => ignoredFields.contains(field.name)) // TODO find out what this is ?
      fields.map { field =>
        val fieldValue = unboxIfNeeded(field.toValueInfo)
        s"${field.name} = ${format(fieldValue, depth - 1)}"
      }.mkString(prefix + "{", ", ", "}")
    }
  }
}

/**
 * Handles LMS classes better
 */
trait LMSFormatter extends ScalaVMFormatter {
  import DynamicWrappers._

  override def format(value: ValueInfoProfile, depth: Int): String = value.typeInfo.name match {
    case SymClassName =>
      new ValueScope(value).as[mock.lms.Expressions.Sym].toString
    case ConstClassName =>
      val fake = new ValueScope(value).as[mock.lms.Expressions.Const[ValueInfoProfile]]
      s"Const(${format(fake.x, depth - 1)})"
    case ClassTypeManifestClassName =>
      val fullName = new ValueScope(value).runtimeClass.name.as[String]
      //println(fullName)
      if (fullName == null) {
        "Manifest[*unknown*]"
      } else {
        "\"" + Definitions.classPrefix(fullName) + "\""
      }
    case BlockClassName =>
      val res = value.toObjectInfo.field("res").toValueInfo
      s"Block(res = ${format(res, depth - 1)})"
    case SummaryClassName =>
      import mock.lms.Effects._
      val local = new ValueScope(value).as[Summary]
      local match {
        case Pure    => "Summary.Pure"
        case Simple  => "Summary.Simple"
        case Global  => "Summary.Global"
        case Alloc   => "Summary.Alloc"
        case Control => "Summary.Control"
        case summary => summary.toString
      }

    case _ => super.format(value, depth)
  }
}

object Printer {

  private val formatter = new LMSFormatter {}

  /**
   * Returns a string representing value. Returns the expected representation
   * when the type has an easy string representation (like null, void or
   * primitive types).
   * If value is an object, returns a string similar to product.toString
   */
  def str(value: ValueInfoProfile): String = {
    Utils.printDebug(s"Printing value -> ${value.toPrettyString}")
    formatter.format(value)
  }

// FIXME HACK
  def strln(value: ValueInfoProfile): String = {
    formatter
      .format(value)
      .replaceAllLiterally(",", ",\n")
      .replaceAllLiterally("{", "{\n ")
      .replaceAllLiterally("}", "\n}")
  }
}
