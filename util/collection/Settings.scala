/* sbt -- Simple Build Tool
 * Copyright 2011 Mark Harrah
 */
package sbt

	import Types._

sealed trait Settings[Scope]
{
	def data: Map[Scope, AttributeMap]
	def keys(scope: Scope): Set[AttributeKey[_]]
	def scopes: Set[Scope]
	def definingScope(scope: Scope, key: AttributeKey[_]): Option[Scope]
	def allKeys[T](f: (Scope, AttributeKey[_]) => T): Seq[T]
	def get[T](scope: Scope, key: AttributeKey[T]): Option[T]
	def set[T](scope: Scope, key: AttributeKey[T], value: T): Settings[Scope]
}

private final class Settings0[Scope](val data: Map[Scope, AttributeMap], val delegates: Scope => Seq[Scope]) extends Settings[Scope]
{
	def scopes: Set[Scope] = data.keySet.toSet
	def keys(scope: Scope) = data(scope).keys.toSet
	def allKeys[T](f: (Scope, AttributeKey[_]) => T): Seq[T] = data.flatMap { case (scope, map) => map.keys.map(k => f(scope, k)) } toSeq;

	def get[T](scope: Scope, key: AttributeKey[T]): Option[T] =
		delegates(scope).toStream.flatMap(sc => scopeLocal(sc, key) ).headOption
	def definingScope(scope: Scope, key: AttributeKey[_]): Option[Scope] =
		delegates(scope).toStream.filter(sc => scopeLocal(sc, key).isDefined ).headOption

	private def scopeLocal[T](scope: Scope, key: AttributeKey[T]): Option[T] =
		(data get scope).flatMap(_ get key)

	def set[T](scope: Scope, key: AttributeKey[T], value: T): Settings[Scope] =
	{
		val map = (data get scope) getOrElse AttributeMap.empty
		val newData = data.updated(scope, map.put(key, value))
		new Settings0(newData, delegates)
	}
}
// delegates should contain the input Scope as the first entry
// this trait is intended to be mixed into an object
trait Init[Scope]
{
	/** The Show instance used when a detailed String needs to be generated.  It is typically used when no context is available.*/
	def showFullKey: Show[ScopedKey[_]]

	final case class ScopedKey[T](scope: Scope, key: AttributeKey[T]) extends KeyedInitialize[T] {
		def scopedKey = this
	}

	type SettingSeq[T] = Seq[Setting[T]]
	type ScopedMap = IMap[ScopedKey, SettingSeq]
	type CompiledMap = Map[ScopedKey[_], Compiled]
	type MapScoped = ScopedKey ~> ScopedKey
	type ValidatedRef[T] = Either[Undefined, ScopedKey[T]]
	type ValidatedInit[T] = Either[Seq[Undefined], Initialize[T]]
	type ValidateRef = ScopedKey ~> ValidatedRef
	type ScopeLocal = ScopedKey[_] => Seq[Setting[_]]
	type MapConstant = ScopedKey ~> Option

	def setting[T](key: ScopedKey[T], init: Initialize[T]): Setting[T] = new Setting[T](key, init)
	def value[T](value: => T): Initialize[T] = new Value(value _)
	def optional[T,U](i: Initialize[T])(f: Option[T] => U): Initialize[U] = new Optional(Some(i), f)
	def update[T](key: ScopedKey[T])(f: T => T): Setting[T] = new Setting[T](key, app(key :^: KNil)(hl => f(hl.head)))
	def app[HL <: HList, T](inputs: KList[Initialize, HL])(f: HL => T): Initialize[T] = new Apply(f, inputs)
	def uniform[S,T](inputs: Seq[Initialize[S]])(f: Seq[S] => T): Initialize[T] = new Uniform(f, inputs)
	def kapp[HL <: HList, M[_], T](inputs: KList[({type l[t] = Initialize[M[t]]})#l, HL])(f: KList[M, HL] => T): Initialize[T] = new KApply[HL, M, T](f, inputs)

	// the following is a temporary workaround for the "... cannot be instantiated from ..." bug, which renders 'kapp' above unusable outside this source file
	class KApp[HL <: HList, M[_], T] {
		type Composed[S] = Initialize[M[S]]
		def apply(inputs: KList[Composed, HL])(f: KList[M, HL] => T): Initialize[T] = new KApply[HL, M, T](f, inputs)
	}

	def empty(implicit delegates: Scope => Seq[Scope]): Settings[Scope] = new Settings0(Map.empty, delegates)
	def asTransform(s: Settings[Scope]): ScopedKey ~> Id = new (ScopedKey ~> Id) {
		def apply[T](k: ScopedKey[T]): T = getValue(s, k)
	}
	def getValue[T](s: Settings[Scope], k: ScopedKey[T]) = s.get(k.scope, k.key) getOrElse error("Internal settings error: invalid reference to " + showFullKey(k))
	def asFunction[T](s: Settings[Scope]): ScopedKey[T] => T = k => getValue(s, k)

	def compiled(init: Seq[Setting[_]], actual: Boolean = true)(implicit delegates: Scope => Seq[Scope], scopeLocal: ScopeLocal, display: Show[ScopedKey[_]]): CompiledMap =
	{
		// prepend per-scope settings 
		val withLocal = addLocal(init)(scopeLocal)
		// group by Scope/Key, dropping dead initializations
		val sMap: ScopedMap = grouped(withLocal)
		// delegate references to undefined values according to 'delegates'
		val dMap: ScopedMap = if(actual) delegate(sMap)(delegates, display) else sMap
		// merge Seq[Setting[_]] into Compiled
		compile(dMap)
	}
	def make(init: Seq[Setting[_]])(implicit delegates: Scope => Seq[Scope], scopeLocal: ScopeLocal, display: Show[ScopedKey[_]]): Settings[Scope] =
	{
		val cMap = compiled(init)(delegates, scopeLocal, display)
		// order the initializations.  cyclic references are detected here.
		val ordered: Seq[Compiled] = sort(cMap)
		// evaluation: apply the initializations.
		applyInits(ordered)
	}
	def sort(cMap: CompiledMap): Seq[Compiled] =
		Dag.topologicalSort(cMap.values)(_.dependencies.map(cMap))

	def compile(sMap: ScopedMap): CompiledMap =
		sMap.toSeq.map { case (k, ss) =>
			val deps = ss flatMap { _.dependsOn } toSet;
			val eval = (settings: Settings[Scope]) => (settings /: ss)(applySetting)
			(k, new Compiled(k, deps, eval))
		} toMap;

	def grouped(init: Seq[Setting[_]]): ScopedMap =
		((IMap.empty : ScopedMap) /: init) ( (m,s) => add(m,s) )

	def add[T](m: ScopedMap, s: Setting[T]): ScopedMap =
		m.mapValue[T]( s.key, Nil, ss => append(ss, s))

	def append[T](ss: Seq[Setting[T]], s: Setting[T]): Seq[Setting[T]] =
		if(s.definitive) s :: Nil else ss :+ s

	def addLocal(init: Seq[Setting[_]])(implicit scopeLocal: ScopeLocal): Seq[Setting[_]] =
		init.flatMap( _.dependsOn flatMap scopeLocal )  ++  init
		
	def delegate(sMap: ScopedMap)(implicit delegates: Scope => Seq[Scope], display: Show[ScopedKey[_]]): ScopedMap =
	{
		def refMap(refKey: ScopedKey[_], isFirst: Boolean) = new ValidateRef { def apply[T](k: ScopedKey[T]) =
			delegateForKey(sMap, k, delegates(k.scope), refKey, isFirst)
		}
		val undefineds = new collection.mutable.ListBuffer[Undefined]
		val f = new (SettingSeq ~> SettingSeq) { def apply[T](ks: Seq[Setting[T]]) =
			ks.zipWithIndex.map{ case (s,i) =>
				(s validateReferenced refMap(s.key, i == 0) ) match {
					case Right(v) => v
					case Left(l) => undefineds ++= l; s
				}
			}
		}
		val result = sMap mapValues f
		if(undefineds.isEmpty) result else throw Uninitialized(sMap, delegates, undefineds.toList)
	}
	private[this] def delegateForKey[T](sMap: ScopedMap, k: ScopedKey[T], scopes: Seq[Scope], refKey: ScopedKey[_], isFirst: Boolean): Either[Undefined, ScopedKey[T]] = 
	{
		def resolve(search: Seq[Scope]): Either[Undefined, ScopedKey[T]] =
			search match {
				case Seq() => Left(Undefined(refKey, k))
				case Seq(x, xs @ _*) =>
					val sk = ScopedKey(x, k.key)
					val definesKey = (refKey != sk || !isFirst) && (sMap contains sk)
					if(definesKey) Right(sk) else resolve(xs)
			}
		resolve(scopes)
	}
		
	private[this] def applyInits(ordered: Seq[Compiled])(implicit delegates: Scope => Seq[Scope]): Settings[Scope] =
		(empty /: ordered){ (m, comp) => comp.eval(m) }

	private[this] def applySetting[T](map: Settings[Scope], setting: Setting[T]): Settings[Scope] =
	{
		val value = setting.init.evaluate(map)
		val key = setting.key
		map.set(key.scope, key.key, value)
	}

	def showUndefined(u: Undefined, sMap: ScopedMap, delegates: Scope => Seq[Scope])(implicit display: Show[ScopedKey[_]]): String =
	{
		val guessed = guessIntendedScope(sMap, delegates, u.referencedKey)
		display(u.referencedKey) + " from " + display(u.definingKey) + guessed.map(g => "\n     Did you mean " + display(g) + " ?").toList.mkString
	}

	def guessIntendedScope(sMap: ScopedMap, delegates: Scope => Seq[Scope], key: ScopedKey[_]): Option[ScopedKey[_]] =
	{
		val distances = sMap.keys.toSeq.flatMap { validKey => refinedDistance(delegates, validKey, key).map( dist => (dist, validKey) ) }
		distances.sortBy(_._1).map(_._2).headOption
	}
	def refinedDistance(delegates: Scope => Seq[Scope], a: ScopedKey[_], b: ScopedKey[_]): Option[Int]  =
		if(a.key == b.key)
		{
			val dist = delegates(a.scope).indexOf(b.scope)
			if(dist < 0) None else Some(dist)
		}
		else None

	final class Uninitialized(val undefined: Seq[Undefined], msg: String) extends Exception(msg)
	final class Undefined(val definingKey: ScopedKey[_], val referencedKey: ScopedKey[_])
	def Undefined(definingKey: ScopedKey[_], referencedKey: ScopedKey[_]): Undefined = new Undefined(definingKey, referencedKey)
	def Uninitialized(sMap: ScopedMap, delegates: Scope => Seq[Scope], keys: Seq[Undefined])(implicit display: Show[ScopedKey[_]]): Uninitialized =
	{
		assert(!keys.isEmpty)
		val suffix = if(keys.length > 1) "s" else ""
		val keysString = keys.map(u => showUndefined(u, sMap, delegates)).mkString("\n\n  ", "\n\n  ", "")
		new Uninitialized(keys, "Reference" + suffix + " to undefined setting" + suffix + ": " + keysString + "\n ")
	}
	final class Compiled(val key: ScopedKey[_], val dependencies: Iterable[ScopedKey[_]], val eval: Settings[Scope] => Settings[Scope])
	{
		override def toString = showFullKey(key)
	}

	sealed trait Initialize[T]
	{
		def dependsOn: Seq[ScopedKey[_]]
		def apply[S](g: T => S): Initialize[S]
		def mapReferenced(g: MapScoped): Initialize[T]
		def validateReferenced(g: ValidateRef): ValidatedInit[T]
		def zip[S](o: Initialize[S]): Initialize[(T,S)] = zipWith(o)((x,y) => (x,y))
		def zipWith[S,U](o: Initialize[S])(f: (T,S) => U): Initialize[U] =
			new Apply[T :+: S :+: HNil, U]( { case t :+: s :+: HNil => f(t,s)}, this :^: o :^: KNil)
		def mapConstant(g: MapConstant): Initialize[T]
		def evaluate(map: Settings[Scope]): T
	}
	object Initialize
	{
		implicit def joinInitialize[T](s: Seq[Initialize[T]]): JoinInitSeq[T] = new JoinInitSeq(s)
		final class JoinInitSeq[T](s: Seq[Initialize[T]])
		{
			def joinWith[S](f: Seq[T] => S): Initialize[S] = uniform(s)(f)
			def join: Initialize[Seq[T]] = uniform(s)(idFun)
		}
		def join[T](inits: Seq[Initialize[T]]): Initialize[Seq[T]] = uniform(inits)(idFun)
		def joinAny[M[_]](inits: Seq[Initialize[M[T]] forSome { type T }]): Initialize[Seq[M[_]]] =
			join(inits.asInstanceOf[Seq[Initialize[M[Any]]]]).asInstanceOf[Initialize[Seq[M[T] forSome { type T }]]]
	}
	object SettingsDefinition {
		implicit def unwrapSettingsDefinition(d: SettingsDefinition): Seq[Setting[_]] = d.settings
		implicit def wrapSettingsDefinition(ss: Seq[Setting[_]]): SettingsDefinition = new SettingList(ss)
	}
	sealed trait SettingsDefinition {
		def settings: Seq[Setting[_]]
	}
	final class SettingList(val settings: Seq[Setting[_]]) extends SettingsDefinition
	final class Setting[T](val key: ScopedKey[T], val init: Initialize[T]) extends SettingsDefinition
	{
		def settings = this :: Nil
		def definitive: Boolean = !init.dependsOn.contains(key)
		def dependsOn: Seq[ScopedKey[_]] = remove(init.dependsOn, key)
		def mapReferenced(g: MapScoped): Setting[T] = new Setting(key, init mapReferenced g)
		def validateReferenced(g: ValidateRef): Either[Seq[Undefined], Setting[T]] = (init validateReferenced g).right.map(newI => new Setting(key, newI))
		def mapKey(g: MapScoped): Setting[T] = new Setting(g(key), init)
		def mapInit(f: (ScopedKey[T], T) => T): Setting[T] = new Setting(key, init(t => f(key,t)))
		def mapConstant(g: MapConstant): Setting[T] = new Setting(key, init mapConstant g)
		override def toString = "setting(" + key + ")"
	}

		// mainly for reducing generated class count
	private[this] def validateReferencedT(g: ValidateRef) =
		new (Initialize ~> ValidatedInit) { def apply[T](i: Initialize[T]) = i validateReferenced g }

	private[this] def mapReferencedT(g: MapScoped) =
		new (Initialize ~> Initialize) { def apply[T](i: Initialize[T]) = i mapReferenced g }

	private[this] def mapConstantT(g: MapConstant) =
		new (Initialize ~> Initialize) { def apply[T](i: Initialize[T]) = i mapConstant g }

	private[this] def evaluateT(g: Settings[Scope]) =
		new (Initialize ~> Id) { def apply[T](i: Initialize[T]) = i evaluate g }

	private[this] def dependencies(ls: Seq[Initialize[_]]): Seq[ScopedKey[_]] = ls.flatMap(_.dependsOn)

	sealed trait Keyed[S, T] extends Initialize[T]
	{
		def scopedKey: ScopedKey[S]
		protected def transform: S => T
		final def dependsOn = scopedKey :: Nil
		final def apply[Z](g: T => Z): Initialize[Z] = new GetValue(scopedKey, g compose transform)
		final def evaluate(ss: Settings[Scope]): T = transform(getValue(ss, scopedKey))
		final def mapReferenced(g: MapScoped): Initialize[T] = new GetValue( g(scopedKey), transform)
		final def validateReferenced(g: ValidateRef): ValidatedInit[T] = g(scopedKey) match {
			case Left(un) => Left(un :: Nil)
			case Right(nk) => Right(new GetValue(nk, transform))
		}
		final def mapConstant(g: MapConstant): Initialize[T] = g(scopedKey) match {
			case None => this
			case Some(const) => new Value(() => transform(const))
		}
		@deprecated("Use scopedKey.")
		def scoped = scopedKey
	}
	private[this] final class GetValue[S,T](val scopedKey: ScopedKey[S], val transform: S => T) extends Keyed[S, T]
	trait KeyedInitialize[T] extends Keyed[T, T] {
		protected final val transform = idFun[T]
	}
	
	private[this] final class Optional[S,T](a: Option[Initialize[S]], f: Option[S] => T) extends Initialize[T]
	{
		def dependsOn = dependencies(a.toList)
		def apply[Z](g: T => Z): Initialize[Z] = new Optional[S,Z](a, g compose f)
		def evaluate(ss: Settings[Scope]): T = f(a map evaluateT(ss).fn)
		def mapReferenced(g: MapScoped) = new Optional(a map mapReferencedT(g).fn, f)
		def validateReferenced(g: ValidateRef) = Right( new Optional(a flatMap { _.validateReferenced(g).right.toOption }, f) )
		def mapConstant(g: MapConstant): Initialize[T] = new Optional(a map mapConstantT(g).fn, f)
	}
	private[this] final class Value[T](value: () => T) extends Initialize[T]
	{
		def dependsOn = Nil
		def mapReferenced(g: MapScoped) = this
		def validateReferenced(g: ValidateRef) = Right(this)
		def apply[S](g: T => S) = new Value[S](() => g(value()))
		def mapConstant(g: MapConstant) = this
		def evaluate(map: Settings[Scope]): T = value()
	}
	private[this] final class Apply[HL <: HList, T](val f: HL => T, val inputs: KList[Initialize, HL]) extends Initialize[T]
	{
		def dependsOn = dependencies(inputs.toList)
		def mapReferenced(g: MapScoped) = mapInputs( mapReferencedT(g) )
		def apply[S](g: T => S) = new Apply(g compose f, inputs)
		def mapConstant(g: MapConstant) = mapInputs( mapConstantT(g) )
		def mapInputs(g: Initialize ~> Initialize): Initialize[T] = new Apply(f, inputs transform g)
		def evaluate(ss: Settings[Scope]) = f(inputs down evaluateT(ss))
		def validateReferenced(g: ValidateRef) =
		{
			val tx = inputs transform validateReferencedT(g)
			val undefs = tx.toList.flatMap(_.left.toSeq.flatten)
			val get = new (ValidatedInit ~> Initialize) { def apply[T](vr: ValidatedInit[T]) = vr.right.get }
			if(undefs.isEmpty) Right(new Apply(f, tx transform get)) else Left(undefs)
		}
	}

	private[this] final class KApply[HL <: HList, M[_], T](val f: KList[M, HL] => T, val inputs: KList[({type l[t] = Initialize[M[t]]})#l, HL]) extends Initialize[T]
	{
		type InitializeM[T] = Initialize[M[T]]
		type VInitM[T] = ValidatedInit[M[T]]
		def dependsOn = dependencies(unnest(inputs.toList))
		def mapReferenced(g: MapScoped) = mapInputs( mapReferencedT(g) )
		def apply[S](g: T => S) = new KApply[HL, M, S](g compose f, inputs)
		def evaluate(ss: Settings[Scope]) = f(inputs.transform[M]( nestCon(evaluateT(ss)) ))
		def mapConstant(g: MapConstant) = mapInputs(mapConstantT(g))
		def mapInputs(g: Initialize ~> Initialize): Initialize[T] =
			new KApply[HL, M, T](f, inputs.transform[({type l[t] = Initialize[M[t]]})#l]( nestCon(g) ))
		def validateReferenced(g: ValidateRef) =
		{
			val tx = inputs.transform[VInitM](nestCon(validateReferencedT(g)))
			val undefs = tx.toList.flatMap(_.left.toSeq.flatten)
			val get = new (VInitM ~> InitializeM) { def apply[T](vr: VInitM[T]) = vr.right.get }
			if(undefs.isEmpty)
				Right(new KApply[HL, M, T](f, tx transform get))
			else
				Left(undefs)
		}
		private[this] def unnest(l: List[Initialize[M[T]] forSome { type T }]): List[Initialize[_]] = l.asInstanceOf[List[Initialize[_]]]
	}
	private[this] final class Uniform[S, T](val f: Seq[S] => T, val inputs: Seq[Initialize[S]]) extends Initialize[T]
	{
		def dependsOn = dependencies(inputs)
		def mapReferenced(g: MapScoped) = new Uniform(f, inputs map mapReferencedT(g).fn)
		def validateReferenced(g: ValidateRef) =
		{
			val (undefs, ok) = List.separate(inputs map validateReferencedT(g).fn )
			if(undefs.isEmpty) Right( new Uniform(f, ok) ) else Left(undefs.flatten)
		}
		def apply[S](g: T => S) = new Uniform(g compose f, inputs)
		def mapConstant(g: MapConstant) = new Uniform(f, inputs map mapConstantT(g).fn)
		def evaluate(ss: Settings[Scope]) = f(inputs map evaluateT(ss).fn )
	}
	private def remove[T](s: Seq[T], v: T) = s filterNot (_ == v)
}
