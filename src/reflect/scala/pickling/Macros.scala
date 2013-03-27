package scala.pickling

import scala.reflect.macros.AnnotationMacro
import scala.reflect.runtime.{universe => ru}
import ir._

// purpose of this macro: implementation of genPickler[T]. i.e. the macro that is selected
// via implicit search and which initiates the process of generating a pickler for a given type T
// NOTE: dispatch is done elsewhere. picklers generated by genPickler[T] only know how to process T
// but not its subclasses or the types convertible to it!
trait PicklerMacros extends Macro {
  def impl[T: c.WeakTypeTag](format: c.Tree): c.Tree = preferringAlternativeImplicits {
    import c.universe._
    import definitions._
    val tpe = weakTypeOf[T]
    val sym = tpe.typeSymbol.asClass
    import irs._
    val pickler = {
      val picklerPid = syntheticPackageName
      val picklerName = syntheticPicklerName(tpe)
      introduceTopLevel(picklerPid, picklerName) {
        def unifiedPickle = { // NOTE: unified = the same code works for both primitives and objects
          val cir = classIR(tpe)
          val beginEntry = q"""
            builder.hintTag(scala.reflect.runtime.universe.typeTag[$tpe])
            builder.beginEntry(picklee)
          """
          val putFields = cir.fields.flatMap(fir => {
            if (sym.isModuleClass) {
              Nil
            } else if (fir.hasGetter) {
              def putField(getterLogic: Tree) = {
                def wrap(pickleLogic: Tree) = q"builder.putField(${fir.name}, b => $pickleLogic)"
                wrap {
                  if (fir.tpe.typeSymbol.isEffectivelyFinal) q"""
                    b.hintStaticallyElidedType()
                    $getterLogic.pickleInto(b)
                  """ else q"""
                    val subPicklee = $getterLogic
                    if (subPicklee == null || subPicklee.getClass == classOf[${fir.tpe}]) b.hintDynamicallyElidedType() else ()
                    subPicklee.pickleInto(b)
                  """
                }
              }
              if (fir.isPublic) List(putField(q"picklee.${TermName(fir.name)}"))
              else reflectively("picklee", fir)(fm => putField(q"$fm.get.asInstanceOf[${fir.tpe}]"))
            } else {
              // NOTE: this means that we've encountered a primary constructor parameter elided in the "constructors" phase
              // we can do nothing about that, so we don't serialize this field right now leaving everything to the unpickler
              // when deserializing we'll have to use the Unsafe.allocateInstance strategy
              Nil
            }
          })
          val endEntry = q"builder.endEntry()"
          q"""
            import scala.reflect.runtime.universe._
            $beginEntry
            ..$putFields
            $endEntry
          """
        }
        def pickleLogic = tpe match {
          case NothingTpe => c.abort(c.enclosingPosition, "cannot pickle Nothing") // TODO: report the serialization path that brought us here
          case _ => unifiedPickle
        }
        q"""
          class $picklerName extends scala.pickling.Pickler[$tpe] {
            import scala.pickling._
            import scala.pickling.`package`.PickleOps
            implicit val format = new ${format.tpe}()
            def pickle(picklee: $tpe, builder: PickleBuilder): Unit = $pickleLogic
          }
        """
      }
    }
    q"new $pickler"
  }
}

// purpose of this macro: implementation of genUnpickler[T]. i.e., the macro that is selected via implicit
// search and which initiates the process of generating an unpickler for a given type T.
// NOTE: dispatch is done elsewhere. unpicklers generated by genUnpickler[T] only know how to process T
// but not its subclasses or the types convertible to it!
trait UnpicklerMacros extends Macro {
  def impl[T: c.WeakTypeTag](format: c.Tree): c.Tree = preferringAlternativeImplicits {
    import c.universe._
    import definitions._
    val tpe = weakTypeOf[T]
    val targs = tpe match { case TypeRef(_, _, targs) => targs; case _ => Nil }
    val sym = tpe.typeSymbol.asClass
    import irs._
    val unpickler = {
      val unpicklerPid = syntheticPackageName
      val unpicklerName = syntheticUnpicklerName(tpe)
      introduceTopLevel(unpicklerPid, unpicklerName) {
        def unpicklePrimitive = q"reader.readPrimitive(tag)"
        def unpickleObject = {
          def readField(name: String, tpe: Type) = q"reader.readField($name).unpickle[$tpe]"

          // TODO: validate that the tpe argument of unpickle and weakTypeOf[T] work together
          // NOTE: step 1) this creates an instance and initializes its fields reified from constructor arguments
          val cir = classIR(tpe)
          val isPreciseType = targs.length == sym.typeParams.length && targs.forall(_.typeSymbol.isClass)
          val canCallCtor = !cir.fields.exists(_.isErasedParam) && isPreciseType
          val pendingFields = cir.fields.filter(fir => fir.isNonParam || (!canCallCtor && fir.isReifiedParam))
          val instantiationLogic = {
            if (sym.isModuleClass) {
              q"${sym.module}"
            } else if (canCallCtor) {
              val ctorSig = cir.fields.filter(_.param.isDefined).map(fir => (fir.param.get: Symbol, fir.tpe)).toMap
              val ctorArgs = {
                if (ctorSig.isEmpty) List(List())
                else {
                  val ctorSym = ctorSig.head._1.owner.asMethod
                  ctorSym.paramss.map(_.map(f => readField(f.name.toString, ctorSig(f))))
                }
              }
              q"new $tpe(...$ctorArgs)"
            } else {
              q"scala.concurrent.util.Unsafe.instance.allocateInstance(classOf[$tpe]).asInstanceOf[$tpe]"
            }
          }
          // NOTE: step 2) this sets values for non-erased fields which haven't been initialized during step 1
          val initializationLogic = {
            if (sym.isModuleClass || pendingFields.isEmpty) instantiationLogic
            else {
              val instance = TermName(tpe.typeSymbol.name + "Instance")
              val initPendingFields = pendingFields.flatMap(fir => {
                val readFir = readField(fir.name, fir.tpe)
                if (fir.isPublic && fir.hasSetter) List(q"$instance.${TermName(fir.name)} = $readFir")
                else reflectively(instance, fir)(fm => q"$fm.set($readFir)")
              })
              q"""
                val $instance = $instantiationLogic
                ..$initPendingFields
                $instance
              """
            }
          }
          q"$initializationLogic"
        }
        def unpickleLogic = tpe match {
          case NullTpe => q"null"
          case NothingTpe => c.abort(c.enclosingPosition, "cannot unpickle Nothing") // TODO: report the deserialization path that brought us here
          case _ if sym.isPrimitive || sym == StringClass => q"$unpicklePrimitive"
          case _ => q"$unpickleObject"
        }
        q"""
          class $unpicklerName extends scala.pickling.Unpickler[$tpe] {
            import scala.pickling._
            import scala.pickling.ir._
            import scala.reflect.runtime.universe._
            implicit val format = new ${format.tpe}()
            def unpickle(tag: TypeTag[_], reader: PickleReader): Any = $unpickleLogic
          }
        """
      }
    }
    q"new $unpickler"
  }
}

// purpose of this macro: implementation of PickleOps.pickle and pickleInto. i.e., this exists so as to:
// 1) perform dispatch based on the type of the argument
// 2) insert a call in the generated code to the genPickler macro (described above)
trait PickleMacros extends Macro {
  def pickle[T: c.WeakTypeTag](format: c.Tree): c.Tree = {
    import c.universe._
    val tpe = weakTypeOf[T]
    val q"${_}($pickleeArg)" = c.prefix.tree
    q"""
      import scala.pickling._
      val picklee: $tpe = $pickleeArg
      val builder = $format.createBuilder()
      picklee.pickleInto(builder)
      builder.result()
    """
  }
  def pickleInto[T: c.WeakTypeTag](builder: c.Tree): c.Tree = {
    import c.universe._
    import definitions._
    val tpe = weakTypeOf[T].widen // TODO: I used widen to make module classes work, but I don't think it's okay to do that
    val sym = tpe.typeSymbol.asClass
    val q"${_}($pickleeArg)" = c.prefix.tree

    def createPickler(tpe: Type) = q"implicitly[Pickler[$tpe]]"
    def finalDispatch = {
      if (sym.isNotNull) createPickler(tpe)
      else q"if (picklee != null) ${createPickler(tpe)} else ${createPickler(NullTpe)}"
    }
    def nonFinalDispatch = {
      val nullDispatch = CaseDef(Literal(Constant(null)), EmptyTree, createPickler(NullTpe))
      val compileTimeDispatch = compileTimeDispatchees(tpe) filter (_ != NullTpe) map (subtpe =>
        CaseDef(Bind(TermName("clazz"), Ident(nme.WILDCARD)), q"clazz == classOf[$subtpe]", createPickler(subtpe))
      )
      //TODO OPTIMIZE: do getClass.getClassLoader only once
      val runtimeDispatch = CaseDef(Ident(nme.WILDCARD), EmptyTree, q"Pickler.genPickler(getClass.getClassLoader, clazz)")
      // TODO: do we still want to use something like HasPicklerDispatch?
      q"""
        val clazz = if (picklee != null) picklee.getClass else null
        ${Match(q"clazz", nullDispatch +: compileTimeDispatch :+ runtimeDispatch)}
      """
    }
    val dispatchLogic = if (sym.isEffectivelyFinal) finalDispatch else nonFinalDispatch

    q"""
      import scala.pickling._
      val picklee = $pickleeArg
      val pickler = $dispatchLogic
      pickler.asInstanceOf[Pickler[$tpe]].pickle(picklee, $builder)
    """
  }
}

// purpose of this macro: implementation of unpickle method on type Pickle, which does
// 1) dispatch to the correct unpickler based on the type of the input,
// 2) insert a call in the generated code to the genUnpickler macro (described above)
trait UnpickleMacros extends Macro {
  // TODO: implement this
  // override def onInfer(tic: c.TypeInferenceContext): Unit = {
  //   c.error(c.enclosingPosition, "must specify the type parameter for method unpickle")
  // }
  def pickleUnpickle[T: c.WeakTypeTag]: c.Tree = {
    import c.universe._
    val tpe = weakTypeOf[T]
    val pickleArg = c.prefix.tree
    q"""
      val pickle = $pickleArg
      val format = new ${pickleFormatType(pickleArg)}()
      val reader = format.createReader(pickle, scala.reflect.runtime.currentMirror)
      reader.unpickle[$tpe]
    """
  }
  def readerUnpickle[T: c.WeakTypeTag]: c.Tree = {
    import c.universe._
    import definitions._
    val tpe = weakTypeOf[T]
    val sym = tpe.typeSymbol.asClass
    val readerArg = c.prefix.tree

    def createUnpickler(tpe: Type) = q"implicitly[Unpickler[$tpe]]"
    def finalDispatch = {
      if (sym.isNotNull) createUnpickler(tpe)
      else q"if (tag != scala.reflect.runtime.universe.typeTag[Null]) ${createUnpickler(tpe)} else ${createUnpickler(NullTpe)}"
    }
    def nonFinalDispatch = {
      val compileTimeDispatch = compileTimeDispatchees(tpe) map (subtpe => {
        // TODO: do we still want to use something like HasPicklerDispatch (for unpicklers it would be routed throw tpe's companion)?
        CaseDef(Literal(Constant(subtpe.key)), EmptyTree, createUnpickler(subtpe))
      })
      val runtimeDispatch = CaseDef(Ident(nme.WILDCARD), EmptyTree, q"Unpickler.genUnpickler(reader.mirror, tag)")
      Match(q"tag.key", compileTimeDispatch :+ runtimeDispatch)
    }
    val dispatchLogic = if (sym.isEffectivelyFinal) finalDispatch else nonFinalDispatch

    q"""
      val reader = $readerArg
      reader.hintTag(scala.reflect.runtime.universe.typeTag[$tpe])
      ${if (sym.isEffectivelyFinal) (q"reader.hintStaticallyElidedType()": Tree) else q""}
      val tag = reader.beginEntry()
      val unpickler = $dispatchLogic
      val result = unpickler.unpickle(tag, reader)
      reader.endEntry()
      result.asInstanceOf[$tpe]
    """
  }
}

trait PickleableMacro extends AnnotationMacro {
  def impl = {
    import c.universe._
    import Flag._
    c.annottee match {
      case ClassDef(mods, name, tparams, Template(parents, self, body)) =>
        // TODO: implement PickleableBase methods and append them to body
        ClassDef(mods, name, tparams, Template(parents :+ tq"scala.pickling.PickleableBase", self, body))
    }
  }
}
