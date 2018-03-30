package fixedWidth

import java.io.*
import kotlin.reflect.*

annotation class Columns(val from: Int, val to: Int)

@Serializable(StringParser::class)
data class Person(
    @Columns( 1,  4) val id: Int,
    @Columns( 5, 15) val name: String,
    @Columns(16, 18) val age: Int
)

@SerializationQualifier(Columns::class)
fun String.readString(from: Int, to: Int): String =
    substring(from - 1, to).trimEnd()

@SerializationQualifier(Columns::class)
fun String.readInt(from: Int, to: Int): Int =
    substring(from - 1, to).trimStart().toInt()

interface StringParser<T> {
    fun String.read(): T
}

@InjectSerializer
fun <T> String.read(reader: StringParser<T>) = with(reader) { read() }

object __PersonStringParser : StringParser<Person> {
    override fun String.read(): Person {
        val id = readInt(1, 4)
        val name = readString(5, 15)
        val age = readInt(16, 18)
        return Person(id, name, age)
    }
}

// --- test code ---

fun main(args: Array<String>) {
    val text = StringReader("""
        |   1Elvis       42
        |   2Jesus       33
        """.trimMargin()
    )
    val persons = text.readLines().map { it.read(__PersonStringParser) }
    println(persons)
}

// ---- aux annotations ---

annotation class Serializable(vararg val serializers: KClass<*>)
annotation class SerializationQualifier(vararg val annotations: KClass<*>)
annotation class InjectSerializer

