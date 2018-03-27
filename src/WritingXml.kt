package writingXml

import java.io.*
import kotlin.reflect.*

@Serializable(XmlWriter::class)
data class Date(val year: Int, val month: Int, val day: Int)

@Serializable(XmlWriter::class)
data class Person(val name: String, val age: Int, val born: Date, val dead: Date)

interface XmlWriter<T> {
    fun XmlOutput.write(obj: T)
}

@InjectSerializer
fun <T> XmlOutput.write(obj: T, writer: XmlWriter<T>) = with(writer) { write(obj) }

inline fun <T> XmlOutput.child(name: String, block: XmlOutput.() -> T): T {
    print("<$name>")
    return block().also {
        println("</$name>")
    }
}

fun XmlOutput.writeValue(value: Any?) = print(value)

fun XmlOutput.beginWrite(name: String) {
    if (!newLine) println()
    level++
    println("<$name>")
    level++
}

fun XmlOutput.endWrite(name: String) {
    level--
    println("</$name>")
    level--
}

object __DateXmlWriter : XmlWriter<Date> {
    override fun XmlOutput.write(obj: Date) {
        beginWrite("Date")
        child("year") { writeValue(obj.year) }
        child("month") { writeValue(obj.month) }
        child("day") { writeValue(obj.day) }
        endWrite("Date")
    }
}

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

// --- test code ---

fun main(args: Array<String>) {
    val sw = StringWriter()
    val output = XmlOutput(sw)
    val person = Person("Elvis", 42,
        born = Date(1935, 1, 8),
        dead = Date(1977, 8, 16)
    )
    output.write(person, __PersonXmlWriter)
    println(sw)
}

// ---- aux annotations ---

annotation class Serializable(vararg val interfaces: KClass<*>)
annotation class InjectSerializer

// ---- writer for human-readable xml ---

class XmlOutput(val out: Writer) {
    var level = -1
    var newLine = true

    fun print(msg: Any?) {
        if (newLine) {
            newLine = false
            repeat(level) { out.write("    ") }
        }
        out.write(msg.toString())
    }

    fun println(msg: Any? = "") {
        print(msg)
        out.write("\n")
        newLine = true
    }
}