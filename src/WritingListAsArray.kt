package writingListAsArray

import java.io.*
import kotlin.reflect.*

@Serializable(ObjectNotationWriter::class)
class Person(val name: String, val age: Int)

@Serializable(ObjectNotationWriter::class)
class Persons(val list: List<Person>)

interface ObjectNotationWriter<T> {
    fun PrintWriter.write(obj: T)
}

@InjectSerializer
fun <T> PrintWriter.write(obj: T, writer: ObjectNotationWriter<T>) = with(writer) { write(obj) }

fun PrintWriter.child(name: String) = this.also { print("$name:") }
fun PrintWriter.writeValue(value: Any?) = print(value)

fun PrintWriter.beginWrite() = print("{")
fun PrintWriter.nextWrite() = print(",")
fun PrintWriter.endWrite() = print("}")

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

object __PersonObjectNotationWriter : ObjectNotationWriter<Person> {
    override fun PrintWriter.write(obj: Person) {
        beginWrite()
        child("name").writeValue(obj.name)
        nextWrite()
        child("age").writeValue(obj.age)
        endWrite()
    }
}

object __PersonsObjectNotationWriter : ObjectNotationWriter<Persons> {
    override fun PrintWriter.write(obj: Persons) {
        beginWrite()
        child("list").writeList(obj.list, __PersonObjectNotationWriter)
        endWrite()
    }
}

// --- test code ---

fun main(args: Array<String>) {
    val sw = StringWriter()
    val output = PrintWriter(sw)
    val persons = Persons(listOf(
        Person("Elvis", 42),
        Person("Jesus", 33))
    )
    output.write(persons, __PersonsObjectNotationWriter)
    println(sw)
}

// ---- aux annotations ---

annotation class Serializable(vararg val interfaces: KClass<*>)
annotation class InjectSerializer
