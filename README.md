# Serialization by Convention

**THIS IS A DRAFT DOCUMENT**: Beware of typos and other work in progress.

This document details the alternative approach to Kotlin Serialization that does not rely of pre-defined interfaces
like `KInput` and `KOutput`, but adapts to arbitrary input/output APIs via extensions and by convention.

> Note: All function and annotation names in this document are tentative.
It is designed to outline the general idea, and it is not a final design. 

## Table of contents

* [Introduction](#introduction)
  * [Goals](#goals)
* [Simple binary serialization](#simple-binary-serialization)
  * [Showcase](#showcase)
  * [Digression on type classes](#digression-on-type-classes)
  * [Simple binary deserialization](#simple-binary-deserialization)
  * [Serializing object graphs](#serializing-object-graphs)
  * [Nullable types](#nullable-types)    
  * [Automatically derived implementations](#automatically-derived-implementations)  
  * [Serializing collections and arrays](#serializing-collections-and-arrays) 
* [Objects as maps](#objects-as-maps)
  * [Map primer](#map-primer)
  * [Reading objects from DB](#reading-objects-from-db)
  * [Alternative storage types](#alternative-storage-types)
  * [Configuration graph from Properties](#configuration-graph-from-properties)
  * [Nullable configuration properties](#nullable-configuration-properties)
  * [Child operator](#child-operator)
  * [Enums](#enums)
  * [Optional properties](#optional-properties)
* [Streaming](#streaming)
  * [Writing JSON](#writing-JSON)
  * [Reading JSON](#reading-json)
  * [Custom serial names](#custom-serial-names)
  * [Arbitrary order of keys](#arbitrary-order-of-keys)
  * [Lists as JSON arrays](#lists-as-JSON-arrays)
  * [XML and friends](#xml-and-friends)
  * [Protobuf and friends](#protobuf-and-friends)     
* [Exotic formats](#exotic-formats)  
  * [Fixed width text files](#fixed-width-text-files)
* [Summary](#summary)
  * [Serializer interfaces](#serializer-interfaces)
  * [Operator functions](#operator-functions) 
  * [Qualifiers](#qualifiers)
  * [Open issues](#open-issues) 
                     
## Introduction

> In computer science, in the context of data storage, serialization is the process of translating data 
structures or object state into a format that can be stored (for example, in a file or memory buffer) or
transmitted (for example, across a network connection link) and reconstructed later 
(possibly in a different computer environment). [Wikipedia](https://en.wikipedia.org/wiki/Serialization)

Serializing objects in/out of some storage requires either use of reflection, manually
writing boiler plate code or using code-generation. We want serialization that is safe, fast and portable beyond JVM, 
so reflection is out of the question. We don't want to manually write boiler plate. It is error-prone. 

So we are choosing code-generation. It has to compile-time code-generation for maximal portability.
The goal is that for simple serialization tasks writing `class Person(val name: String, age: Int)` and
adding some sort of annotation should be enough get a code that reads and writes instances of this class 
generated.

The challenge is that there are lot of serialization formats out there. It is not sufficient for us
to support some kind of "Kotlin Serialization" format. We'd like support popular formats like JSON and Protobuf, 
as well as exotic ones like fixed-width text files or FIX. 
Serialization formats can come in various shapes and forms:

* Format can be binary or text based.
* Format can be performance-sensitive (optimized for high-throughput) or not.
* Format can locate properties based their name, integer tags, some other way,
  or simply based on the order of properties in a class. 
* Format can be driven by the class components as they were known at compile time or can be 
  driven by external metadata. 
  
A single class can be read (deserialized) into an object from a database row, transferred to frontend 
application in JSON (serialized on a backend and deserialized in a frontend), 
stored in some binary format in its cache and presented in table on a screen (which can be also implemented
as an instance of serialization).

### Goals

Design goals for Kotlin serialization are:

* Cross-platform (JVM, JS, Native).
* Reflection is optional (most use-cases shall work without it), static types only by default.
* Serialization-format agnostic.
* Works with non-augmented classes, classes that can be used for any other purposes in application.
* Simple things should be simple, complex and exotic things shall be supported.
* High-performance, but see above &mdash; it is Ok is performance sensitive code need to be more complicated, 
  but it is not Ok if clarity of all the code suffers.
* Clear way to plug your own serialization format.  
* Most common formats should be supported out-of-the box (JSON and Protobuf for sure).  
  
Non-goals:

* Out-of-the box support for variety of standard formats.  
* Support arbitrary object graphs with cycles.
                  
## Simple binary serialization

In this chapter we'll walk though the use-case of serializing classes in a simple binary format. 
There are no schemas, no property names or tags, just objects-in bytes-out and vice versa.

### Showcase

Let's start with the following Kotlin class for our initial showcase:

```kotlin
class Person(val name: String, val age: Int)
```

Suppose that we want to write this class to a simple binary storage via `java.io.DataOutputStream` class that 
implements `java.io.DataOutput` interface. This interface already defines writing methods for primitive types
like `writeInt(Int)` and `writeUTF(String)`.

We want the compiler, at compile time, to walk all the properties of `Person` class in their program order 
and implement the following function for us, without us having to  write this boilerplate, so that we can  
`write` a `Person` to the `DataOutput` as if `DataOuput` had just supported writing instances of a `Person`:

```kotlin
fun DataOutput.write(obj: Person) {
    writeUTF(obj.name)
    writeInt(obj.age)
}
```

Defining this function directly as a top-level function does reach the goal for being able to write individual
classes and even static hierarchies of classes, but deprives us of ability to work with classes dynamically,
when the actual type is know only at runtime, and, as a corollary (due to type erasure) makes it impossible
to support generic types. So we take a slightly indirect path to having compiler implement that function for us.  

> A use-case for such a genericity is shown in 
[Serializing collections and arrays](#serializing-collections-and-arrays) section. 

We start by defining a "writer" serialization interface with the prototype of that function 
that the compiler is going to implement for us:

```kotlin
interface DataOutputWriter<in T> {
    fun DataOutput.write(obj: T)
}
```

> We assume here that `write` is the name that compiler recognizes. In the final design we might want
to protect against accidental typos by using `operator` modifier on this extension `fun write` or some annotation. 

We instruct the compiler to generate the implementation of this interface by adding an annotation
to the class:

```kotlin
@Serializable(DataOutputWriter::class)
class Person(val name: String, val age: Int)
```

This causes automatic generation of an object that implements `DataOutputWriter` interface for a `Person` class:

```kotlin
object __PersonDataOutputWriter : DataOutputWriter<Person> {
    override fun DataOutput.write(obj: Person) {
        writeUTF(obj.name)
        writeInt(obj.age)
    }
}
```

> The compiler finds `writeXXX` on the output type by convention. The name is expected to start with `write`, 
like `writeInt`. Note, that `writeUTF` is recognized as the most specific function to write a `String` because 
of the type of its argument, not because of it name.  
Here `writeXXX` methods are  resolved on a Java type `java.io.DataOutput` and it makes sense to use purely 
name-based match. For conventional methods defined in Kotlin classes and interfaces we might want to consider having 
`operator` modifier or some annotation. 

> Here we ignore the complication that `java.io.DataOutput` has a "wrong" `write(Int)` method that should not
be used for serialization. We assume that it is never resolved by the compiler as serialization `write` function
via some mechanism that is out of the scope of this showcase.

The actual name of the generated object (`__PersonDataOutputWriter` here) is going to be internal and should not be
directly accessible to the Kotlin code. But we do need a way indirectly access it from Kotlin. 
We define the following extension function:

```kotlin
@InjectSerializer
fun <T> DataOutput.write(obj: T, writer: DataOutputWriter<T>) = with(writer) { write(obj) }
```  
 
This function is trivial and does not do anything, but delegates `write` invocation on `DataOutput` interface
to the implementation of `DataOutputWriter`. It is marked with `@InjectSerializer` annotation in this example,
because it is a truly "magical" function requiring a specialized compiler support beyond what one would expect
from a function marked by `operator` modifier as show below. We can now write the following code:

```
val person = Person("Elvis", 42)
val output = DataOutputStream(...)
output.write(person) // __PersonDataOutputWriter is injected
``` 

We write the code as if `DataOutput` interface has `DataOutput.write(obj: Person)` function just like
we originally wanted to.  This code omits the last actual parameter to the `write` extension function that we've 
defined. That is the key magic here and that is what `@InjectSerializer` for. 
The compiler automatically injects an appropriate serializer and expands the last line to:

```kotlin
output.write(person, __PersonDataOutputWriter)
``` 

Here `__PersonDataOutputWriter` is the actual object where the compiler had generated implementation 
for `DataOutputWriter<Person>` interface.

> You can find worked out example of code from this section in [src/SimpleBinary.kt](src/SimpleBinary.kt)

### Digression on type classes

The `DataOutputWriter` interface is a good example of a _type-class_ (aka extension interface). When type-classes are 
supported by the Kotlin language as discussed in [KEEP-87](https://github.com/Kotlin/KEEP/pull/87) this interface
declaration from the previous example would look like this:

```kotlin
__extension interface DataOutputWriter<in T> {
    fun DataOutput.write(obj: T)
}
```

Now, a declaration of the `write` extension function would not be needed at all, nor any of the `@InjectSerializer` magic 
to add an additional `DataOutputWriter` parameter to that invocation. All this magic is going to be 
a part of compiler support for type-classes. The following code:

```kotlin
output.write(person)
```  

would just work when the instance of `DataOutputWriter` type-class for `Person` is "in scope", that is it is either 
imported into the global scope, or a part of the function's scope, or brought into the scope explicitly 
using `with(instance) { ... }` or a similar scoping function.

In fact, it would possible to avoid the need for a serialization-specific `@Serializable(DataOutputWriter::class)` annotation.
Serialization can become a part of Kotlin compiler's support for automatic derivation of certain type-classes:

```kotlin
class Person(val name: String, val age: Int) __deriving DataOutputWriter
```

> All the syntax in this section is purely provisional. Kotlin type-classes are currently in early pre-design
discussion phase.

### Simple binary deserialization

Deserializing an object with a simple binary format from `DataInput` requires us to take similar steps. 
First we define a "reader" interface:
 
```kotlin
interface DataInputReader<out T> {
    fun DataInput.read(): T
}
```

Add this interface to a `Serializable` annotation to instruct compiler to implement it for us:

```kotlin
@Serializable(DataOutputWriter::class, DataInputReader::class)
data class Person(val name: String, val age: Int)
```

and compiler generates:

```kotlin
object __PersonDataInputReader : DataInputReader<Person> {
    override fun DataInput.read(): Person {
        val name = readUTF()
        val age = readInt()
        return Person(name, age)
    }
}
```

> In a similar way to writer, compiler looks up `readXXX` methods on the `DataInput` and uses their return type to 
find the most specific ones for the statically known types of `Person` properties.

Define "magic" reader function where compiler can add a reference to its implementation of 
`DataInputReader` interface for us (and which will not be needed with type-classes):

```kotlin
@InjectSerializer
fun <T> DataInput.read(reader: DataInputReader<T>) = with(reader) { read() }
```

Now we can write the following code, given the appropriate compiler magic to insert the actual implementation
of the `DataInputReader` interface as the last parameter of the `read` function:

```kotlin
val input = DataInputStream(/*...*/)
val person = input.read<Person>() // __PersonDataInputReader is injected
```

The following annotation that we had to include on a `Person` class looks daunting:

```kotlin
@Serializable(DataOutputWriter::class, DataInputReader::class)
```

we can make it somewhat simpler by defining a serializer interface that extends both 
`DataOutputWriter` and `DataInputReader`:

```kotlin
interface DataIO<T> : DataOutputWriter<T>, DataInputReader<T>
```

Now we can write a simpler incantation:

```kotlin
@Serializable(DataIO::class)
class Person(val name: String, val age: Int)
```

> It is still somewhat wordy. We can find alternative approaches to shorten this annotation using `@Serialization` as
a meta-annotation to define `@DataSerializable` annotation for classes that can be written to `DataOutput` and read
from `DataInput`. There are also design options to mark serializable classes _en masse_ (all in a given file, for example).

> You can find worked out example of code from this section in [src/SimpleBinary.kt](src/SimpleBinary.kt)

### Serializing object graphs

Lets take a look at an example of a serializable object graph:

```kotlin
@Serializable(DataIO::class)
class Date(val year: Int, val month: Int, val day: Int) 

@Serializable(DataIO::class)
class Person(val name: String, val age: Int, val born: Date, val dead: Date)
```

Compiler generates the following code:

```kotlin
object __DateDataIO : DataIO<Date> {
    override fun DataOutput.write(obj: Date) {
        writeInt(obj.year)
        writeInt(obj.month)
        writeInt(obj.day)
    }

    override fun DataInput.read(): Date {
        val year = readInt()
        val month = readInt()
        val day = readInt()
        return Date(year, month, day)
    }
}

object __PersonDataIO : DataIO<Person> {
    override fun DataOutput.write(obj: Person) {
        writeUTF(obj.name)
        writeInt(obj.age)
        write(obj.born, __DateDataIO)
        write(obj.dead, __DateDataIO)
    }

    override fun DataInput.read(): Person {
        val name = readUTF()
        val age = readInt()
        val born = read(__DateDataIO)
        val dead = read(__DateDataIO)
        return Person(name, age, born, dead)
    }
}
```

Now we can write to `DataOutput` and read from `DataInput` both `Date` and `Person`:

```kotlin
val person = Person("Elvis", 42, 
    born = Date(1935, 1, 8),
    dead = Date(1977, 8, 16))
output.write(person) // __PersonDataIO is injected
```

> You can find worked out example of code from this section in [src/ObjectGraph.kt](src/ObjectGraph.kt)

### Nullable types

Now we realize that a person in our data model can be still alive and so the date of their death should be nullable,
so we change definition of our classes:

```kotlin
@Serializable(DataIO::class)
class Date(val year: Int, val month: Int, val day: Int) 

@Serializable(DataIO::class)
class Person(val name: String, val age: Int, val born: Date, val dead: Date?)
```

It will not work, because compiler will not be able to resolve `writeXXX` method on `DataOutput` that is capable
of writing a value of nullable `Date?` type.

Let us define a function to write nullable value as an extension on `DataOutput`. We will write an 
additional byte before a nullable type's value:

```kotlin
@InjectSerializer
fun <T : Any> DataOutput.writeNullable(obj: T?, writer: DataOutputWriter<T>) {
    if (obj == null) {
        writeByte(0)
    } else {
        writeByte(1)
        with(writer) { write(obj) }
    }
}
```

and a corresponding function to read nullable value as an extension on `DataInput`:

```kotlin
@InjectSerializer
fun <T : Any> DataInput.readNullable(reader: DataInputReader<T>): T? =
    if (readByte().toInt() == 0) null else with(reader) { read() }
```

> Note, that we are free to name these functions in any way as long as their name starts with `write` and `read` 
as compiler resolves them by the type of parameter (for `write`) and the type of result (for `read`) correspondingly.

> With type-classes we still have to write these functions, but we would not use `@InjectSerializer` annotation,
but some type-class specific syntax instead, like 
`fun <T : Any> DataOutput.writeNullable(obj: T?) with DataOutputWriter<T>` as an example of such syntax.

These two extensions is all we need to support serialization of our updated `Person` class with a nullable 
`dead` property. See how compiler is using `writeNullable` and `readNullable` for `dead` property, since
these are the only functions capable of writing/reading nullable types and they are marked 
with `@InjectSerializer` so the last parameter is injected automatically:

```kotlin
object __PersonDataIO : DataIO<Person> {
    override fun DataOutput.write(obj: Person) {
        writeUTF(obj.name)
        writeInt(obj.age)
        write(obj.born, __DateDataIO)
        writeNullable(obj.dead, __DateDataIO)
    }

    override fun DataInput.read(): Person {
        val name = readUTF()
        val age = readInt()
        val born = read(__DateDataIO)
        val dead = readNullable(__DateDataIO)
        return Person(name, age, born, dead)
    }
}
```

> You can find worked out example of code from this section in [src/NullableTypes.kt](src/NullableTypes.kt)

### Automatically derived implementations

Consider the following serializable class:

```kotlin
@Serializable(DataIO::class)
class MaybeInt(val value: Int?) 
```

How would compiler generate an implementation of `DataIO` interface for `MaybeInt`? There is no `DataOutput.writeXXX`
function that takes `Int?` as a parameter. There is `DataOutput.writeNullable` that takes an arbitrary nullable
value _and_ an implementation of `DataOutputWriter` for the corresponding non-nullable type, but we don't
have an implementation of `DataOutputWriter<Int>`. 

However, `DataOutput` interface contains `writeInt` method and `DataInput` has a corresponding `readInt`. They
can be used to automatically derive trivial implementation of both `DataOutputWriter<Int>` and `DataInputReader<Int>`:

```kotlin
object __IntDataIO : DataOutputWriter<Int>, DataInputReader<Int> {
    override fun DataOutput.write(obj: Int) = writeInt(obj)
    override fun DataInput.read(): Int = readInt()
}
```

> The challenging design decision here is where and when compiler shall emit those automatically derived implementations.
Implementing them on "as needed" basis (once per module?) can result in lots of duplication in larger projects, so 
it looks reasonable to emit them right where the corresponding `DataOutputWriter` and `DataInputReader` interfaces
are defined. Their definitions provide enough context. They refer to the `DataInput`/`DataOutput` interfaces that
can be immediately analyzed, all their `writeXXX` and `readXXX` methods found, and, for each type they support,
the corresponding trivial implementations can be derived. 
The only thing that is lacking here is any kind of a signal to the compiler that it shall be done.
 
> This strongly suggests that `DataOutputWriter` and `DataInputReader`
interfaces shall be marked with some dedicated annotation to provide such a signal to the compiler.
This annotation would prompt compiler to emit the corresponding automatically generated implementations for all 
the data types supported by `writeXXX` and `readXXX` functions.

> But what happens with externally defined `writeXXX`/`readXXX` extensions to `DataOutput`/`DataInput` interfaces? 
They can bring support for new writeable/readable data types. Every time such extensions are defined, compiler shall 
generate the corresponding implementation of `DataOutputWriter` and `DataInputReader` interface for the
corresponding data type. But how would compiler know about the interface it has to implement? This suggests that
whenever an external `writeXXX`/`readXXX` extension is defined, it has to be somehow annotated with an 
annotation that refers to `DataOutputWriter` or `DataInputReader` interface or some other alternative approach 
shall be found. 

Now `DataIO<MaybeInt>` implementation can be generated by compiler when it sees 
`@Serializable(DataIO::class) class MaybeInt(val value: Int?)`:

```kotlin
object __MaybeIntDataIO : DataIO<MaybeInt> {
    override fun DataOutput.write(obj: MaybeInt) {
        writeNullable(obj.value, __IntDataIO)
    }

    override fun DataInput.read(): MaybeInt {
        val value = readNullable(__IntDataIO)
        return MaybeInt(value)
    }
}
```

> You can find worked out example of code from this section in [src/AutomaticallyDerived.kt](src/AutomaticallyDerived.kt)

This is not very efficient, as writing a value of `Int?` type involves going through a chain of two generic interface
calls, but it does allow us to manually write minimal amount of code and reuse the logic for encoding of nullable
value (we define it just once).

In the rare case when we care about every tick of performance, we can always define a type-specific extension:

```kotlin
fun DataOutput.writeNullableInt(obj: Int?) {
    if (obj == null) {
        writeByte(0)
    } else {
        writeByte(1)
        writeInt(obj)
    }
}

fun DataInput.readNullableInt(): Int? =
    if (readByte().toInt() == 0) null else readInt()
```

Here we had traded some cut-and-pasting of the null-encoding logic for fully statically dispatched call when 
writing/reading value of `Int?` type. The resolution rules of `writeXXX` and `readXXX` functions shall ensure that 
these extensions are treated as the most specific ones and are used to generate `DataIO<MaybeInt>` implementation:

```kotlin
object __MaybeIntDataIO : DataIO<MaybeInt> {
    override fun DataOutput.write(obj: MaybeInt) {
        writeNullableInt(obj.value)
    }

    override fun DataInput.read(): MaybeInt {
        val value = readNullableInt()
        return MaybeInt(value)
    }
}
```

> You can find worked out example of code from this section in [src/AutomaticallyDerived2.kt](src/AutomaticallyDerived2.kt)

### Serializing collections and arrays

Let us try to serialize the following graph:

```kotlin
@Serializable(DataIO::class)
class Person(val name: String, val age: Int)

@Serializable(DataIO::class)
class Persons(val list: List<Person>)
```

In order to generate `DataIO<Persons>` implementation compiler needs `DataOuput.writeXXX` function that 
takes a `List` and `DataInput.readXXX` function that returns a `List`. The simplest way to 
get it working is to mnaullay provide the corresponding extensions:

```kotlin
@InjectSerializer
fun <T> DataOutput.writeList(list: List<T>, writer: DataOutputWriter<T>) {
    val n = list.size
    writeInt(n)
    for (i in 0 until n) write(list[i], writer)
}

@InjectSerializer
fun <T> DataInput.readList(reader: DataInputReader<T>): List<T> {
    val n = readInt()
    val list = ArrayList<T>(n)
    for (i in 0 until n) list += read(reader)
    return list
}
```

> Note how we ask compiler to inject implementation of `DataOutputWriter<T>`/`DataInputReader<T>` that
can serialize `T` and then we provide serializing functions that work for `List<T>`.

Now compiler can generate serializer for `Persons` class with a `List<Person>` property:

```kotlin
object __PersonsDataIO : DataIO<Persons> {
    override fun DataOutput.write(obj: Persons) {
        writeList(obj.list, __PersonDataIO)
    }

    override fun DataInput.read(): Persons {
        val list = readList(__PersonDataIO)
        return Persons(list)
    }
}
```

> You can find worked out example of code from this section in [src/CollectionsManual.kt](src/CollectionsManual.kt)

This approach is great for cases when the format has native support for some specific type and either performance
or special representation is needed for this particular type. However, it is a lot of boiler-plate to define those
serializer functions for all kinds of collections every time we need to plug in support for new format. 
The boiler-plate reducing solution is have compiler automatically generate collection serializers using a 
hard-coded pattern, unless they were provided explicitly in user code:

```kotlin
class __ListDataOutputWriter<in T>(private val writer: DataOutputWriter<T>) : DataOutputWriter<List<T>> {
    override fun DataOutput.write(obj: List<T>) {
        val n = obj.size
        writeInt(n)
        for (i in 0 until n) write(obj[i], writer)
    }
}

class __ListDataInputReader<out T>(private val reader: DataInputReader<T>) : DataInputReader<List<T>> {
    override fun DataInput.read(): List<T> {
        val n = readInt()
        val list = ArrayList<T>(n)
        for (i in 0 until n) list += read(reader)
        return list
    }
}
``` 

Now compiler can generate serializer for `Persons` in the following way:

```kotlin
object __PersonsDataIO : DataIO<Persons> {
    override fun DataOutput.write(obj: Persons) {
        write(obj.list, __ListDataOutputWriter(__PersonDataIO))
    }

    override fun DataInput.read(): Persons {
        val list = read(__ListDataInputReader(__PersonDataIO))
        return Persons(list)
    }
}
```

> You can find worked out example of code from this section in [src/CollectionsAutomatic.kt](src/CollectionsAutomatic.kt)

> Note how a complex generic serializer is automatically build and injected by the compiler. This is a good example,
among others, on the generic type-classes and compiler's ability to resolve and build their instances.
In a world of type-classes that would correspond to ability to define type-class instances that depend 
on other instance like `__extension class __ListDataOutputWriter<in T> : DataOutputWriter<List<T>> with DataOutputWriter<T>`
for an example of syntax.

> Here we fundamentally use the fact that we have defined `DataOutputWriter` and `DataInputReader` interfaces. 
It allows us to avoid generating serializers for specific instances of lists like `List<Person>`, but 
to serialize lists in a _generic_ way, by combining a generic serializer for `List<T>` with an implementation 
of the corresponding serializer interface for `Person`. This approach is _composable_ and avoids explosion 
of combinations between various types of collections and other generic contains and various data classes 
that can be stored in those collections.

> The question of when and how these automatic list serializers shall be generated is an open issue here, too.

## Objects as maps

In this chapter we'll see how objects can be converted to/from maps and other map-list data structures, 
which includes various DOM-like hierarchical structures, database rows, configuration property files,
tables in UI, etc. Converting objects to/from text formats like JSON and XML can be performed in this way, too.

### Map primer

Let us see what is the minimum amount of code we need to write to get objects converted to and from 
`Map<String,Any?>`. We need to define interfaces that compiler is going to implement for us:

```kotlin
interface MapWriter<in T> {
    fun MutableMap<String,Any?>.write(obj: T)
}

interface MapReader<out T> {
    fun Map<String,Any?>.read(): T
}
```

> Note that `MapWriter` defines extension for a `MutableMap<String,Any?>` while 
`MapReader` defines an extension for read-only `Map<String,Any?>` 

We would also define an umbrella interface: 

```kotlin
interface MapIO<T> : MapWriter<T>, MapReader<T>
```

We cannot yet ask the compiler to generate `MapIO` implementation for us, because `Map<String,Any?>` type does
not have any `writeXXX` or `readXXX` methods to write/read individual fields. Let us define them as extensions:

```kotlin
fun <T> MutableMap<String,Any?>.write(name: String, value: T) {
    this[name] = value
}

fun <T> Map<String,Any?>.read(name: String): T {
    check(contains(name)) { "Expected property '$name' is not found"}
    return this[name] as T
}
```

We have something new here. We've already seen generic `write` and `read` functions, but here these functions are
_qualified_ &mdash; they have an additional `name: String` which is where compiled code is going to put the 
name of the property that is being serialized.

> We could use `KProperty<*>` instead of `String` here, but we don't need anything beyond property's 
_serial name_ here, which can be different from its actual property name.  
For dense and performance-optimized serialization formats an extra indirection will be noticeable on benchmarks,
so we show-case higher-performance approach even if it does not matter in this example. 
Moreover, this "serial name injection" is a part of a more generic mechanism of _qualifiers_. 

> It might be better to use here some `inline class Name(val name: String)` wrapper on top of a `String` to be 
more explicit about the nature of `name: String` parameter. 

Now we can ask compiler to generate implementations for us:

```kotlin
@Serializable(MapIO::class)
data class Person(val name: String, val age: Int)
```

and get the following code:

```kotlin
object __PersonMapIO : MapIO<Person> {
    override fun MutableMap<String, Any?>.write(obj: Person) {
        write("name", obj.name)
        write("age", obj.age)
    }

    override fun Map<String, Any?>.read(): Person {
        val name = read<String>("name")
        val age = read<Int>("age")
        return Person(name, age)
    }
}
```

Now, as before, in order to conveniently write an object to a map we need extension functions marked
with `@InjectSerializer`:

```kotlin
@InjectSerializer
fun <T> MutableMap<String,Any?>.write(obj: T, writer: MapWriter<T>) = with(writer) { write(obj) }

@InjectSerializer
fun <T> Map<String,Any?>.read(reader: MapReader<T>): T = with(reader) { read() }
``` 

> Remember, that we would not need them with type-classes.

And we can just write object to and read from a map now:

```kotlin
val person = Person("Elvis", 42)
val map = mutableMapOf<String,Any?>()
map.write(person)
// ...
map.read<Person>()
```

> You can find worked out example of code from this section in [src/MapPrimer.kt](src/MapPrimer.kt)

### Reading objects from DB

We have enough building blocks now to show how we can read our data models from database with JDBC `ResultSet`
without any reflection and without writing boilerplate code that duplicates the names of our data model's properties.

As usual, start by defining serialization interface specific to JDBC `ResultSet`:

```kotlin
interface ResultSetReader<out T> {
    fun ResultSet.read(): T
}
```

Write extensions to read all the primitive types we need:

```kotlin
fun ResultSet.readInt(name: String): Int = getInt(name)
fun ResultSet.readString(name: String): String = getString(name)
```

Define utility function where compiler can inject its serializer implementation:

```kotlin
@InjectSerializer
fun <T> ResultSet.read(reader: ResultSetReader<T>): T = with(reader) { read() }
```

So now we can just annotate our data model classes:

```kotlin
@Serializable(ResultSetReader::class)
class Person(val name: String, val age: Int)
```

Get compiler implement `ResultSetReader` interface for us:

```kotlin
object __PersonResultSetReader : ResultSetReader<Person> {
    override fun ResultSet.read(): Person {
        val name = readString("name")
        val age = readInt("age")
        return Person(name, age)
    }
}
```

And enjoy ability to just read our data model classes from the result set:

```kotlin
val ps = con.prepareStatement("select * from Persons")
val rs = ps.executeQuery()
while (rs.next()) {
    val person = rs.read<Person>()
    // ...
}
```

> You can find worked out example of code from this section in [src/ResultSet.kt](src/ResultSet.kt)

### Alternative storage types

In the SQL databases (and in JDBC) there are multiple ways to represent a `String`. We might want
to store some (long) strings in `CLOB` columns, while still mapping them to `String` values in our
data model for convenience.

The way to achieve it is to define an annotation for an alternative storage type:

```kotlin
annotation class Clob
```

and define a separate serializer function (in our case -- reader function):

```kotlin
@SerializationQualifier(Clob::class)
fun ResultSet.readClob(name: String): String =
    with(getClob(name)) { getSubString(0, length().toInt()) }
``` 

> Note how this function is annotated with `@SerializationQualifier(Clob::class)` so it is resolved only for
properties that are marked with `@Clob` annotation. An alternative design would be to mark `Clob` with a 
meta-annotation, but that is not look flexible enough. See [Fixed width text files](#fixed-width-text-files)
for an example where `@SerializationQualifier` annotation is used in a more powerful way.

Now we can use `@Clob` annotation on the corresponding data model property:

```kotlin
@Serializable(ResultSetReader::class)
data class Person(
    val name: String,
    val age: Int,
    @Clob val notes: String
)
```

This way compiler generates different code read `notes` property as opposed to `name` property, 
even though both have type `String`:

```kotlin
object __PersonResultSetReader : ResultSetReader<Person> {
    override fun ResultSet.read(): Person {
        val name = readString("name")
        val age = readInt("age")
        val notes = readClob("notes")
        return Person(name, age, notes)
    }
}
```

> You can find worked out example of code from this section in [src/StorageTypes.kt](src/StorageTypes.kt)

### Configuration graph from Properties

Let's take a look at the following data model for a hypothetical application's configuration:

```kotlin
class Connection(val host: String, val port: Int)
class Config(val log: Connection, val data: Connection)
```

we'd like to read this configuration from a properties file of the following kind:

```properties
log.host = logger.example.com
log.port = 514
data.host = incoming.example.com
data.port = 7011
```

We cannot just define reading function as extensions on `Properties`, because we need to keep current context
(name prefix) so we define an auxiliary node class to combine this context with an instance of `Properties`. 
This class defines read functions for the primitive types that we plan to use and a function to create a child node:

```kotlin
class Node(private val prop: Properties, private val prefix: String = "") {
    // reading functions
    fun readString(name: String): String = prop.getProperty(key(name))
    fun readInt(name: String): Int = prop.getProperty(key(name)).toInt()
    // auxiliary
    fun child(name: String) = Node(prop, key(name))
    private fun key(name: String) = if (prefix == "") name else "$prefix.$name"
}
```

Our reading interface that compiler implements is defined as an extension for `Node`:

```kotlin
interface NodeReader<T> {
    fun Node.read(): T
}
```

The new concept in this example is that we define an extension to read an object from the `Node` with 
`name: String` parameter:

```kotlin
@InjectSerializer
fun <T> Node.read(name: String, reader: NodeReader<T>) = with(reader) { child(name).read() }
```

When we ask compiler to generate serializers for our classes with `@Serializable(NodeReader::class)` annotation
we get the following code as the result of compiler resolving suitable functions to read 
components for each of the classes:

```kotlin
object __ConnectionNodeReader : NodeReader<Connection> {
    override fun Node.read(): Connection {
        val host = readString("host")
        val port = readInt("port")
        return Connection(host, port)
    }
}

object __ConfigNodeReader : NodeReader<Config> {
    override fun Node.read(): Config {
        val log = read("log", __ConnectionNodeReader)
        val data = read("data", __ConnectionNodeReader)
        return Config(log, data)
    }
}
```

Now we can read our configuration from properties like this:

```kotlin
val config = Node(props).read<Config>("") // __ConfigNodeReader is injected
```

> You can find worked out example of code from this section in [src/ConfigProperties.kt](src/ConfigProperties.kt)

### Nullable configuration properties

Let us make log connector nullable in the above configuration:

```kotlin
class Config(val log: Connection?, val data: Connection)
```

We don't have support for reading nullable (no corresponding `readXXX` function), so let us add it.
First, let us add an additional auxiliary function to `Node`:

```kotlin
class Node(private val prop: Properties, private val prefix: String = "") {
    // ... as before here ... 
    fun hasChild(name: String): Boolean {
        val prefix = key(name) + "."
        return prop.keys.any { it is String && it.startsWith(prefix) }
    }
}
```

> It is not efficient way to check if we have something starting with the given prefix in a map of properties. 
And actual production-ready implementation would convert properties into a hierarchical tree of nodes first, 
and the start parsing it, in which can checking for a child would be efficient.
This implementation is sufficient for our exposition, though. 

Then we define a generic reading function for nullable types in a similar way as we did
in [Nullable types](#nullable-types) section, but with an additional `name: String` parameter:

```kotlin
@InjectSerializer
fun <T> Node.readNullable(name: String, reader: NodeReader<T>): T? =
    if (hasChild(name)) read(name, reader) else null
```

It is enough to get `Config` serializer object generated by compiler for us:

```kotlin
object __ConfigNodeReader : NodeReader<Config> {
    override fun Node.read(): Config {
        val log = readNullable("log", __ConnectionNodeReader)
        val data = read("data", __ConnectionNodeReader)
        return Config(log, data)
    }
}
```

> You can find worked out example of code from this section in [src/NullableConfig.kt](src/NullableConfig.kt)

### Child operator

Let us take a look at support of nullable primitives in a map-like data structure (`Properties`).
What if connection host is nullable:

```kotlin
class Connection(val host: String?, val port: Int)
```

There is no `readXXX` function that returns `String?`. We can always define one for `String?`, but this
approach is not composable as we'll have to repeat this definition for every kind of primitive that we
are supporting in our serialization format. We'd like an have a solution that is based on
[Automatically derived implementations](#automatically-derived-implementations), but compiler cannot
derive a ready-to-use implementation of `NodeReader<String>` based on `fun readString(name: String): String`,
because of its `name: String` parameter.

> The value of this parameter could have been _captured_, but it of little help, since it would not 
compose properly with `fun <T> Node.readNullable(name: String, reader: NodeReader<T>): T?` that also needs
to capture the name.  

Reading configuration is not something where performance matters, so we need an approach that reduces boiler-plate
even if it introduces performance penalty. We introduce `child` operator as a compiler-recognized function
that is named `child` and to get a corresponding node based on property name. We redefine `readString` and
`readInt` functions without `name: String` parameter:

```kotlin
class Node(private val prop: Properties, private val prefix: String = "") {
    // reading functions
    fun readString(): String = prop.getProperty(prefix)
    fun readInt(): Int = prop.getProperty(prefix).toInt()
    // child operator
    fun child(name: String) = Node(prop, key(name))
    // auxiliary
    private fun key(name: String) = if (prefix == "") name else "$prefix.$name"
    fun hasNode(): Boolean = prop.keys.any { it is String && it.startsWith(prefix) }
}
```

Other functions don't need `name: String` anymore:

```kotlin
@InjectSerializer
fun <T> Node.read(reader: NodeReader<T>) = with(reader) { read() }

@InjectSerializer
fun <T> Node.readNullable(reader: NodeReader<T>): T? =
    if (hasNode()) read(reader) else null
``` 

Compiler can now automatically generate a trivial implementation of `NodeReader<String>` interface:

```kotlin
object __StringNodeReader : NodeReader<String> {
    override fun Node.read(): String = readString()
}
```

And use it to generate a serializer for `Connection`:

```kotlin
object __ConnectionNodeReader : NodeReader<Connection> {
    override fun Node.read(): Connection {
        val host = child("host").readNullable(__StringNodeReader)
        val port = child("port").readInt()
        return Connection(host, port)
    }
}
```

See how `readInt("port")` becomes `child("port").readInt()`. If we've left there `fun readInt(name: String): Int`
on `Node` class then it would have been considered as more specific and used, but in the absence of it reading goes 
via `child` operation, because it is present for the `Node` type we are reading from. 

The same transformation happens to `Config` serializer:

```kotlin
object __ConfigNodeReader : NodeReader<Config> {
    override fun Node.read(): Config {
        val log = child("log").readNullable(__ConnectionNodeReader)
        val data = child("data").read(__ConnectionNodeReader)
        return Config(log, data)
    }
}
```

> You can find worked out example of code from this section in [src/ChildOperator.kt](src/ChildOperator.kt)

> We still need support for specifying `name: String` in `readXXX` and `writeXXX` functions explicitly 
for serialization formats where performance is important and where allocating a fresh object for every 
leaf property might be a performance show-stopper.
 
### Enums

Building on a previous configuration examples, let us have the following enum:

```kotlin
enum class Transport { PLAIN, ENCRYPTED }
```

that is used in `Connection` class as `mode` property:

```kotlin
class Connection(val host: String?, val port: Int, val mode: Transport)
```

Our goal is to be able to read the following configuration properties:

```properties
log.host = logger.example.com
log.port = 514
log.mode = plain
data.host = incoming.example.com
data.port = 7011
data.mode = encrypted
```

All we need is to define the following extension to encapsulate the logic of reading enums in our particular
format where we want to refer to enums by name in a case-insensitive manner:

```kotlin
inline fun <reified T : Enum<T>> Node.readEnum(): T =
    enumValueOf(readString().toUpperCase(Locale.ROOT))
```

> We've just used an `inline` function with a `reified` type parameter here. No problems due to 
convention-based nature of the serialization. 

Now, compiler resolves `readEnum` as the way to read property of `Transport` type and 
generates the following reader for `Connection` class:

```kotlin
object __ConnectionNodeReader : NodeReader<Connection> {
    override fun Node.read(): Connection {
        val host = child("host").readNullable(__StringNodeReader)
        val port = child("port").readInt()
        val mode = child("mode").readEnum<Transport>()
        return Connection(host, port, mode)
    }
}
```

> You can find worked out example of code from this section in [src/Enums.kt](src/Enums.kt)

> Automatic composition of this enum support with support of nullable types presents a technical challenge.
Compiler will have to generate `__EnumNodeReader` class based on the `readEnum` function in order to pass
it to `readNullable` function, but there is a `reified` type parameter that cannot be captured in a class at 
the moment (no support for `reified` type parameters for classes). However, if the `__EnumNodeReader` class is 
generated on a lower (non-source) level, then this complication can be easily worked around.
In an ideal world, support of reified type parameters on classes would be needed to make it source-representable.  

### Optional properties

Now, let us support optional properties with default value. 
Let's have a default `Connect.mode` with a value of `Transport.PLAIN`:

```kotlin
data class Connection(val host: String?, val port: Int, val mode: Transport = Transport.PLAIN)
``` 

> Note, that _optional_ properties are distinct from _nullable_ properties. `mode` could have been a nullable 
property with a non-null default value. In this simple format that we have in our example there is no way to distinguish
a missing property that should use a default value from a property that was explicitly set to null. However, 
real-life formats like JSON do make this distinction.

To support optional properties we introduce `canRead` operator function:

```kotlin
fun Node.canRead(): Boolean = hasNode()
```

> As usual, we don't mark operator functions in this document in any way but use name-based match only. 
An actual design should consider marking them somehow.

Presence of this function enables support for optional properties and results in the following 
generated reader for `Connection` class with an optional `mode` property:

```kotlin
object __ConnectionNodeReader : NodeReader<Connection> {
    override fun Node.read(): Connection {
        val host = child("host").readNullable(__StringNodeReader)
        val port = child("port").readInt()
        val mode: Transport = with(child("mode")) {
            if (canRead()) readEnum<Transport>() else Transport.PLAIN
        }
        return Connection(host, port, mode)
    }
}
```

> You can find worked out example of code from this section in [src/Optionals.kt](src/Optionals.kt)

> This is just one code generation strategy for optional properties. Default values of Kotlin classes can contain
arbitrary expressions and this strategy would have to copy them in a generated reader. Another strategy, that 
cannot be directly represented in the source, but is feasible if code is generated in a non-source form, is to 
directly use synthetic constructor of the class with optional parameters and pass a corresponding bitmask to 
indicate whether an optional property was present or not.

## Streaming

Previous chapters had introduced enough concepts to write and read JSON and JSON-like formats to/from 
DOM-like (maps of maps) data structures. It is a good solution for JSON-like configuration formats, 
but it is too slow (and produces too much garbage) for serving and consuming data when working with REST services
in performance-sensitive applications.

In this chapter we'll see how to compose and parse JSON, XML, Protobuf and other keyed or tagged formats in a streaming way without building intermediate DOM-like 
representation.

### Writing JSON

We start with a data mode from [Serializing object graphs](#serializing-object-graphs) section:

```kotlin
class Date(val year: Int, val month: Int, val day: Int)
class Person(val name: String, val age: Int, val born: Date, val dead: Date)
```  

Our goal is to produce the following JSON-like `{key:value}` text output:

```text
{name:Elvis,age:42,born:{year:1935,month:1,day:8},dead:{year:1977,month:8,day:16}}
```

> We totally ignore in this example all the concerns related to quoting keys and values to simplify the code. 

We start with a usual scaffolding, defining extension on `PrintWriter`:

```text
interface ObjectNotationWriter<T> {
    fun PrintWriter.write(obj: T)
}

@InjectSerializer
fun <T> PrintWriter.write(obj: T, writer: ObjectNotationWriter<T>) = with(writer) { write(obj) }
```

> The actual high-performance JSON writer should not use `PrintWriter` which is synchronized.

We use `child` operator to write keys. The `child` operator must return an reference to output
type (see [Child operator](#child-operator) section where it was introduced), but it can also have 
side-effects, which we use here:

```kotlin
fun PrintWriter.child(name: String) = this.also { print("$name:") }
```

In this example we define a function to write value for `Any?` type to save code:

```kotlin
fun PrintWriter.writeValue(value: Any?) = print(value)
```

>  The actual high-performance JSON writer should provide specialized writing function for various
primitive types to avoid boxing. 

However, this is not enough. We need to write `{` at the start of every composite object value, 
write `,` in between its fields, and write `}` at the end of it. It is achieved by the following three operators:

```kotlin
fun PrintWriter.beginWrite() = print("{")
fun PrintWriter.nextWrite() = print(",")
fun PrintWriter.endWrite() = print("}")
```

> As usual, we assume that operators are matched by name. The actual design would use `operator` modifier
or some other approach to mark them.

Because of the presence of those operators on `PrintWriter` output type, for classes annotated with
`@Serializable(ObjectNotationWriter::class)` compiler is going to generate:

```kotlin
object __DateObjectNotationWriter : ObjectNotationWriter<Date> {
    override fun PrintWriter.write(obj: Date) {
        beginWrite()
        child("year").writeValue(obj.year)
        nextWrite()
        child("month").writeValue(obj.month)
        nextWrite()
        child("day").writeValue(obj.day)
        endWrite()
    }
}

object __PersonObjectNotationWriter : ObjectNotationWriter<Person> {
    override fun PrintWriter.write(obj: Person) {
        beginWrite()
        child("name").writeValue(obj.name)
        nextWrite()
        child("age").writeValue(obj.age)
        nextWrite()
        child("born").write(obj.born, __DateObjectNotationWriter)
        nextWrite()
        child("dead").write(obj.dead, __DateObjectNotationWriter)
        endWrite()
    }
}
```

> You can find worked out example of code from this section in [src/WritingKV.kt](src/WritingKV.kt)

### Reading JSON

We will skim over symmetric approach to parse JSON-like format in a streaming way under assumption that keys
are stored in the program order (just like we've printed them in the previous section). This is not how JSON
works. In actual JSON keys can go in arbitrary order, but there are some real-life tagged-value formats defined like that
for parsing performance reasons. 

The generated readers are going to be symmetric to the corresponding writers. Here is a `Person` reader for example:

```kotlin
object __PersonObjectNotationReader : ObjectNotationReader<Person> {
    override fun Parser.read(): Person {
        beginRead()
        val name = child("name").readString()
        nextRead()
        val age = child("age").readInt()
        nextRead()
        val born = child("born").read(__DateObjectNotationReader)
        nextRead()
        val dead = child("dead").read(__DateObjectNotationReader)
        endRead()
        return Person(name, age, born, dead)
    }
}
```

> You can find worked out example of code from this section in [src/ReadingKV.kt](src/ReadingKV.kt)

> Note, that real-life formats with this kind of structure use keys (tags) to be able to skip missing properties
and fill in default values. They need to read ahead the next tag and implement a _qualified_ `canRead(name: String)`
operator that the compiler will invoke before `child(name).readXXX()` line, unlike unqualified `canRead()` operator
that was shown in [Optional properties](#optional-properties) section.

### Custom serial names

Serial names that are used as `String` _qualifiers_ to serialization operator functions use the name of corresponding
property by default. However, they can be explicitly specified via `@SerialName` annotation. Adding these 
annotations to the `Person` class from [Writing JSON](#writing-JSON) section:

```kotlin
class Person(
    val name: String, 
    val age: Int, 
    @SerialName("birth_date") val born: Date, 
    @SerialName("death_date") val dead: Date
)
```  

would produce the following output:

```text
{name:Elvis,age:42,birth_date:{year:1935,month:1,day:8},death_date:{year:1977,month:8,day:16}}
```

In fact, when we define a _qualified_ `child` operator function like this:

```kotlin
fun PrintWriter.child(name: String)
```

It works as if we've explicitly added `@SerializationQualifier` annotation that was shown
in [Alternative storage types](#alternative-storage-types) section in the following way:

```kotlin
@SerializationQualifier(SerialName::class)
fun PrintWriter.child(name: String)
```

So we call `SerialName` an _implicit qualifier_. There is no harm in specifying it explicitly. 
Moreover, we can define our custom explict qualifiers. For example, we can read instances of `Person` class
from a database and use totally different column names in the database, while still using the same source class:

```kotlin
annotation class DbName(val name: String)

@Serializable(ObjectNotationWriter::class, DbReader::class)
class Person(
    @DbName("NAMED") val name: String, 
    @DbName("AGED") val age: Int, 
    @DbName("BORN") @SerialName("birth_date") val born: Date, 
    @DbName("DEAD") @SerialName("death_date") val dead: Date
)
```

Functions to read this object from `ResultSet` as was shown in [Reading objects from DB](#reading-objects-from-db)
section can be qualified like this:

```kotlin
@SerializationQualifier(DbName::class)
fun ResultSet.readString(name: String): String = getString(name)
``` 

An explicit `SerializationQualifier` annotation here would result in substitution of `name` property from `DbName`
annotation, falling back to implicitly qualified read function or to unqualified read function when `DbName`
annotation is not present.

### Arbitrary order of keys

In actual JSON keys can go in arbitrary order. To support that, we change definition of `nextRead` operator
so that it returns `String?`. The result is the name of the next property to read or `null` when there are 
no more properties in the current object. 

```kotlin
fun Parser.nextRead(): String? { /* ... */ }
```

This change results in a radically different content of generated `Person` reader:

```kotlin
object __PersonObjectNotationReader : ObjectNotationReader<Person> {
    override fun Parser.read(): Person {
        beginRead()
        var name: String? = null
        var age: Int = 0
        var born: Date? = null
        var dead: Date? = null
        var bitMask = 0
        loop@while (true) {
            when (nextRead()) {
                "name" -> {
                    name = readString()
                    bitMask = bitMask or 0x01
                }
                "age" -> {
                    age = readInt()
                    bitMask = bitMask or 0x02
                }
                "born" -> {
                    born = read(__DateObjectNotationReader)
                    bitMask = bitMask or 0x04
                }
                "dead" -> {
                    dead = read(__DateObjectNotationReader)
                    bitMask = bitMask or 0x08
                }
                null -> break@loop
            }
        }
        endRead()
        require(bitMask == 0x0F)
        return Person(name!!, age, born!!, dead!!)
    }
}
```

Now the whole generated `read` function is a loop that collects parsed values in local variables and updates
`bitMask` to track which properties were read. This `bitMask` will be used to fill in values for optional 
properties (see [Optional properties](#optional-properties)) if there were any. Since there are no optional 
properties in this example, it just requires that all properties are present.

> There is an open question on how to handle duplicated properties like `{age:42,age:33}`. In this example the most 
recent value of a duplicated property is used. But what if there is a requirement to report an error in this case?     

> You can find worked out example of code from this section in [src/ArbitraryOrder.kt](src/ArbitraryOrder.kt)

### Lists as JSON arrays

JSON format provides an opportunity to showcase custom serialization of lists that is fully static and 
does not use any kind of reflection. JSON has a special syntax for arrays and we'd like to use it for a 
data model from [Serializing collections and arrays](#serializing-collections-and-arrays) section. 

```kotlin
class Person(val name: String, val age: Int)
class Persons(val list: List<Person>)
```

We define a specialized writing function for lists:

```kotlin
@InjectSerializer
fun <T> PrintWriter.writeList(obj: List<T>, writer: ObjectNotationWriter<T>) {
    print("[")
    var sep = ""
    for (item in obj) {
        print(sep)
        write(item, writer)
        sep = ","
    }
    print("]")
}
```

And it is going to be resolved as the most specific one when generating writer for `Persons`:

```kotlin
object __PersonsObjectNotationWriter : ObjectNotationWriter<Persons> {
    override fun PrintWriter.write(obj: Persons) {
        beginWrite()
        child("list").writeList(obj.list, __PersonObjectNotationWriter)
        endWrite()
    }
}
```

So we get this JSON-line output with a list:

```text
{list:[{name:Elvis,age:42},{name:Jesus,age:33}]}
```

> You can find worked out example of code from this section in [src/WritingListAsArray.kt](src/WritingListAsArray.kt)

### XML and friends

Write XML, unlike JSON, requires us to tags around property value, so we will use alternative signature of
operator function `child` that takes a block of code as the last parameter:

```kotlin
inline fun <T> XmlOutput.child(name: String, block: XmlOutput.() -> T): T {
    print("<$name>")
    return block().also {
        println("</$name>")
    }
}
```

> Implementation of the format is free to pass a different instance of the output type into the block, but we
are not using this ability in this example.

The other difference is that we are going to use _qualified_ `beginWrite` and `endWrite` operators and get rid of 
`nextWrite` which we would not need for XML:

```kotlin
fun XmlOutput.beginWrite(name: String) { /* ... */ }
fun XmlOutput.endWrite(name: String) { /* ... */ }
```

Here, qualifiers are taken for the serializable class itself and `name: String` is substituted
with serial name of the class which is equal to the simple class name by default:

```kotlin
object __PersonXmlWriter : XmlWriter<Person> {
    override fun XmlOutput.write(obj: Person) {
        beginWrite("Person")
        child("name") { writeValue(obj.name) }
        child("age") { writeValue(obj.age) }
        child("born") { write(obj.born, __DateXmlWriter) }
        child("dead") { write(obj.dead, __DateXmlWriter) }
        endWrite("Person")
    }
}
```                                                              

All in all, we are able to produce this maximally verbose XML output:

```xml
<Person>
    <name>Elvis</name>
    <age>42</age>
    <born>
        <Date>
            <year>1935</year>
            <month>1</month>
            <day>8</day>
        </Date>
    </born>
    <dead>
        <Date>
            <year>1977</year>
            <month>8</month>
            <day>16</day>
        </Date>
    </dead>
</Person>
```

> You can find worked out example of code from this section in [src/WritingXml.kt](src/WritingXml.kt)

### Protobuf and friends

Protobuf and similar formats use integer tags as opposed to string keys. That is the major difference from 
the standpoint of serializing them. The fact that Protobuf is a binary format is of a secondary importance, 
but it is important to keep in mind that Protobuf is a performance-sensitive format so generated serialization 
code must be efficient and should not have extra indirection.

In order to specify tags for our classes we define an explicit qualifier annotation:

```kotlin
annotation class Tag(val tag: Int)

@Serializable(ProtoWriter::class)
class Date(
    @Tag(1) val year: Int,
    @Tag(2) val month: Int,
    @Tag(3) val day: Int
)

@Serializable(ProtoWriter::class)
class Person(
    @Tag(1) val name: String,
    @Tag(2) val age: Int,
    @Tag(3) val born: Date,
    @Tag(4) val dead: Date)
```

Now, Protobuf is a very opinionated format in what and how it can represent. Missing fields in Protobuf
are assumed to be `null` and there is no other way to encode nulls or defaults. Representation of collections
and arrays in Protobuf is fancy and cannot be generalized to `List<List<Int>>`. So there is little
sense to attempt a composable solution as was shown in [Nullable types](#nullable-types) and
[Serializing collections and arrays](#serializing-collections-and-arrays) sections.
There is no point is doing implementation via `child` operator.
Instead, all writing functions to support Protobuf are qualified and specialized for each supported type:

```kotlin
@SerializationQualifier(Tag::class)
fun ProtoOutput.writeVarint(tag: Int, value: Int) {
    emitVarint(tag shl 3)
    emitVarint(value)
}

@SerializationQualifier(Tag::class)
fun ProtoOutput.writeString(tag: Int, value: String) {
    emitVarint(tag shl 3 or 2)
    emitBytes(value.toByteArray(Charsets.UTF_8))
}
```

> Note, how we've called primitive function on `ProtoOutput` type `emitVarint`. If we'd called it
`writeVarint` then it would not cause an immediate problem, since a qualified verision is considered
to be more specif, but then we would not get any error on `@Serializable(ProtoWriter::class)` annotated
class with a missing `@Tag` annotation on its properties. Having some kind of operator modifier or
annotation on recognized operator functions would have helped here.

Protobuf is not a well-designed format in terms of composing and parsing performance.
Here we face a challenge of writing Protobuf embedded messages. They have to be
length-encoded, so writing them, in general, requires two passes over the data structure:
first pass to compute size, second pass to actually write them. Google's Protobuf implementation
stores a computed size directly in the classes that represent messages. We don't
have this luxury, since our goal is to work with non-augmented application classes.

We choose a different strategy. We immediately reserve one byte for size (small message optimization),
write embedded message into buffer, then patch the buffer to include size
(shifting written bytes if the size turned out to be more than 127 bytes):

```kotlin
@SerializationQualifier(Tag::class)
@InjectSerializer
fun <T> ProtoOutput.write(tag: Int, obj: T, writer: ProtoWriter<T>) {
    emitVarint(tag shl 3 or 2)
    val pos = this.size
    emit(0) // reserve a byte
    write(obj, writer)
    patchVarint(pos, this.size - pos - 1)
}
```

> Unfortunately, this strategy can get us O(N^2) on very large messages,
so some other approach is needed to efficiently write Protobuf messages.
Design of such an approach is out of the scope of this example.

Compiler generates the following simple serializers for us:

```kotlin
object __DateProtoWriter : ProtoWriter<Date> {
    override fun ProtoOutput.write(obj: Date) {
        writeVarint(1, obj.year)
        writeVarint(2, obj.month)
        writeVarint(3, obj.day)
    }
}

object __PersonProtoWriter : ProtoWriter<Person> {
    override fun ProtoOutput.write(obj: Person) {
        writeString(1, obj.name)
        writeVarint(2, obj.age)
        write(3, obj.born, __DateProtoWriter)
        write(4, obj.dead, __DateProtoWriter)
    }
}
```

This gets us Protobuf bytes that can be decoded in other
languages using the following proto file:

```proto
syntax = "proto3";

message Date {
    int32 year = 1;
    int32 month = 2;
    int32 date = 3;
}

message Person {
    string name = 1;
    int32 age = 2;
    Date born = 3;
    Date dead = 4;
}
```

> You can find worked out example of code from this section in [src/Proto.kt](src/Proto.kt)

## Exotic formats

This chapter we'll cover some exotic format that are not widely used, yet useful in certain niche applications
and their support is a low-handing fruit with can grasp with Kotlin serialization.

### Fixed width text files  
 
Some domains still have fixed-width text files where a database of persons might be encoded like this:

```text
   1Elvis       42
   2Jesus       33    
```

In this example text columns 1-4 carry person id, columns 5-15 name, columns 16-18 age. We'd like to represent 
each person with the following class:

```kotlin
class Person(
    @Columns( 1,  4) val id: Int,
    @Columns( 5, 15) val name: String,
    @Columns(16, 18) val age: Int
)
```  

where `Columns` annotation denotes the columns in the text file:

```kotlin
annotation class Columns(val from: Int, val to: Int)
```

We define reading function directly on `String` using `@SerializationQualifier(Columns::class)` to instruct
compiler to pass the parameters of `Columns` annotation into the corresponding reader functions:

```kotlin
@SerializationQualifier(Columns::class)
fun String.readString(from: Int, to: Int): String =
    substring(from - 1, to).trimEnd()

@SerializationQualifier(Columns::class)
fun String.readInt(from: Int, to: Int): Int =
    substring(from - 1, to).trimStart().toInt()
```

> `@SerializationQualifier` annotation may mention multiple qualifier annotations, in which case their parameters
are concatenated in the respective order and passed to the serializer function. For example, design of 
this particular fixed-width format might find it more readable to have a separate `annotation class From(val from: Int)`
and `annotation class To(val to: Int)`, in which case reader functions will have to be annotated with
`@SerializationQualifier(From::class, To::class)` to designate the order in which parameters are passed into functions.

Reading interface is defined in a usual way together with a helper function:

```kotlin
interface StringParser<T> {
    fun String.read(): T
}

@InjectSerializer
fun <T> String.read(reader: StringParser<T>) = with(reader) { read() }
```

> Note, that the name of the reading interface does not have to end with `Reader`, but the names of the
reading functions have to start with `read`

Now, annotating `Person` with `@Serializable(StringParser::class)` generates the corresponding serializer object:

```kotlin
object __PersonStringParser : StringParser<Person> {
    override fun String.read(): Person {
        val id = readInt(1, 4)
        val name = readString(5, 15)
        val age = readInt(16, 18)
        return Person(id, name, age)
    }
}
```

Converting a string text to a list of objects becomes as simple as 
`text.readLines().map { it.read<Person>() }`

> You can find worked out example of code from this section in [src/FixedWidth.kt](src/FixedWidth.kt)

## Summary

This chapter summarizes all introduced concepts.

### Serializer interfaces

Serializer extension interface with an arbitrary name `S` must have a single unbounded generic type parameter
(here named `T`) and may define the following functions (all are optional, at least one must be present):

```kotlin
interface S<T> {
    fun I.read(): T 
    fun O.write(obj: T)
}
``` 

Here `I` is an input type, and `O` is an output type.

### Operator functions

| Signature                                    | Description / Example                                       
| -------------------------------------------  | ---------------------
| `I.readXXX([qualifiers]): T`                 | Reads typed value from the input `I`                <br> [Showcase](#showcase)
| `O.writeXXX([qualifiers], value: T)`         | Writes typed value to the output `O`                <br> [Simple binary deserialization](#simple-binary-deserialization)
| `IO.child(qualifiers): IO`                   | Narrows input/output type `IO` with qualifiers      <br> [Child operator](#child-operator)
| `IO.child(qualifiers, block: IO.() -> T): T` | -- same --                                          <br> [XML and friends](#xml-and-friends)  
| `I.canRead([qualifiers]): Boolean`           | Checks if input `I` has a value to read             <br> [Optional properties](#optional-properties) 
| `O.beginWrite([qualifiers])`                 | Begins writing composite object                     <br> [Writing JSON](#writing-JSON) 
| `O.nextWrite([qualifiers])`                  | Continues writing composite object (between fields) <br> [Writing JSON](#writing-JSON) 
| `O.endWrite([qualifiers])`                   | Ends writing composite object                       <br> [Writing JSON](#writing-JSON) 
| `I.beginRead([qualifiers])`                  | Begins reading composite object                     <br> [Reading JSON](#reading-json) 
| `I.nextRead([qualifiers])`                   | Continues reading composite object (between fields) <br> [Reading JSON](#reading-json) 
| `I.nextRead([qualifiers]): R`                | Returns next property to read                       <br> [Arbitrary order of keys](#arbitrary-order-of-keys) 
| `I.endRead([qualifiers])`                    | Ends reading composite object                       <br> [Reading JSON](#reading-json) 
                                       
* `T` in `readXXX` and `writeXXX` operator function can be an arbitrary type and those functions can be generic
  with complex dependencies between their generic parameter types and `T`.                                       
* `R` in `nextRead()` operator function can be either `String?` or `Int`.                                                     
                                                          
### Qualifiers 

TBD
 
### Open issues

1. Should operators be marked with `operator` modifier, some other modifier or some annotation or different annotations?
   (see [Showcase](#showcase)).
   
TBD       

 











 



