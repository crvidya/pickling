import scala.pickling._
import binaryopt._
import reflect.runtime.universe._

import scala.language.higherKinds

import scala.collection.generic.CanBuildFrom

case class Person(name: String, age: Int)

object TraversablePicklerBench extends testing.Benchmark {

  implicit def traversablePickler[T: TypeTag, Coll[_] <: Traversable[_]]
    (implicit elemPickler: Pickler[T], elemUnpickler: Unpickler[T],
              pf: PickleFormat, cbf: CanBuildFrom[Coll[_], T, Coll[T]]): Pickler[Coll[T]] with Unpickler[Coll[T]] =
    new Pickler[Coll[T]] with Unpickler[Coll[T]] {

    import scala.reflect.runtime.currentMirror

    // these type aliases seem like unnecessary boilerplate that we might be able to get rid of
    type PickleFormatType = PickleFormat
    type PickleBuilderType = elemPickler.PickleBuilderType
    type PickleReaderType = elemUnpickler.PickleReaderType

    val format: PickleFormat = pf

    def pickle(picklee: Any, builder: PickleBuilderType): Unit = {
      val coll = picklee.asInstanceOf[Coll[T]]
      val tpe  = typeTag[Int] // used to pickle the number of elements
      builder.beginEntryNoType(typeTag[AnyRef], picklee)

      builder.putField("numElems", b => {
        b.beginEntryNoType(tpe, coll.size)
        b.endEntry()
      })

      coll.foreach({ (el: Any) =>
        builder.putField("elem", b => { // in this case, the name "elem" is actually ignored for binary format, would be terrible if `format` was JSON
          elemPickler.pickle(el, b)
        })
      })

      builder.endEntry()
    }

    def unpickle(tpe: TypeTag[_], reader: PickleReaderType): Any = {
      val next = reader.readField("numElems")
      val num  = next.readPrimitive(typeTag[Int]).asInstanceOf[Int]

      var builder = cbf.apply()
      for (i <- 1 to num) {
        reader.readField("elem")

        val tag = reader.readTag(currentMirror)
        val el  = elemUnpickler.unpickle(tag, reader)

        builder += el.asInstanceOf[T]
      }

      builder.result
    }
  }

  val name = "Jim"
  val vec = (1 to 100000).map(i => Person(name+i, i)).toVector

  val pf             = implicitly[BinaryPickleFormat]
  val vecPicklerRaw  = implicitly[Pickler[Vector[Person]]]

  override def run() {
    val builder = pf.createBuilder()
    val vecPickler = vecPicklerRaw.asInstanceOf[Pickler[_]{ type PickleBuilderType = pf.PickleBuilderType }]

    vecPickler.pickle(vec, builder)
    val pckl = builder.result()

    val vecUnpickler = vecPicklerRaw.asInstanceOf[Unpickler[_]{ type PickleBuilderType = BinaryPickleBuilder ; type PickleReaderType = BinaryPickleReader }]

    val res = vecUnpickler.unpickle(typeTag[Vector[Person]], pf.createReader(pckl))
  }
}
