package writingKV

import java.io.*
import kotlin.reflect.*

@Serializable(ObjectNotationWriter::class)
data class Date(val year: Int, val month: Int, val day: Int)

@Serializable(ObjectNotationWriter::class)
data class Person(val name: String, val age: Int, val born: Date, val dead: Date)

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

// --- test code ---

fun main(args: Array<String>) {
    val sw = StringWriter()
    val output = PrintWriter(sw)
    val person = Person("Elvis", 42,
        born = Date(1935, 1, 8),
        dead = Date(1977, 8, 16)
    )
    output.write(person, __PersonObjectNotationWriter)
    println(sw)
}

// ---- aux annotations ---

annotation class Serializable(vararg val serializers: KClass<*>)
annotation class InjectSerializer
