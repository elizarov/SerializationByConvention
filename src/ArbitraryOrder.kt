package arbitraryOrder

import kotlin.reflect.*

@Serializable(ObjectNotationReader::class)
data class Date(val year: Int, val month: Int, val day: Int)

@Serializable(ObjectNotationReader::class)
data class Person(val name: String, val age: Int = -1, val born: Date, val dead: Date)

interface ObjectNotationReader<T> {
    fun Parser.read(): T
}

@InjectSerializer
fun <T> Parser.read(reader: ObjectNotationReader<T>): T = with(reader) { read() }

fun Parser.readString(): String = next()
fun Parser.readInt(): Int = next().toInt()

fun Parser.beginRead() { skip("{") }
fun Parser.endRead() { skip("}") }

fun Parser.nextRead(): String? {
    if (token == "}") return null
    if (token == ",") next()
    val name = next()
    skip(":")
    return name
}

fun Parser.skipRead(name: String) {
    error("Unexpected property '$name'")
}

object __DateObjectNotationReader : ObjectNotationReader<Date> {
    override fun Parser.read(): Date {
        beginRead()
        var year: Int = 0
        var month: Int = 0
        var day: Int = 0
        var bitMask = 0
        loop@while (true) {
            val nextName = nextRead()
            when (nextName) {
                "year" -> {
                    year = readInt()
                    bitMask = bitMask or 0x01
                }
                "month" -> {
                    month = readInt()
                    bitMask = bitMask or 0x02
                }
                "day" -> {
                    day = readInt()
                    bitMask = bitMask or 0x04
                }
                null -> break@loop
                else -> skipRead(nextName)
            }
        }
        endRead()
        require(bitMask == 0x07)
        return Date(year, month, day)
    }
}

object __PersonObjectNotationReader : ObjectNotationReader<Person> {
    override fun Parser.read(): Person {
        beginRead()
        var name: String? = null
        var age: Int = 0
        var born: Date? = null
        var dead: Date? = null
        var bitMask = 0
        loop@while (true) {
            val nextName = nextRead()
            when (nextName) {
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
                else -> skipRead(nextName)
            }
        }
        endRead()
        require(bitMask == 0x0F)
        return Person(name!!, age, born!!, dead!!)
    }
}

// --- test code ---

fun main(args: Array<String>) {
    println(parse("{name:Elvis,age:42,born:{year:1935,month:1,day:8},dead:{year:1977,month:8,day:16}}"))
    println(parse(" { name : Elvis, age : 42, born : { year : 1935 , month : 1 , day : 8 } , dead:{year:1977,month:8,day:16}}"))
    println(parse(" { age : 42, name : Elvis, born : { year : 1935 , month : 1 , day : 8 } , dead:{month:8,day:16,year:1977}}"))
}

private fun parse(s: String): Person = Parser(s).read(__PersonObjectNotationReader)

// ---- aux annotations ---

annotation class Serializable(vararg val interfaces: KClass<*>)
annotation class InjectSerializer


// --- Parser ---

// DO NOT WRITE ACTUAL PARSERS LIKE THIS !!!
// THIS CODE IS HIGHLY INEFFICIENT (but simple)
class Parser(val s: String) {
    var token = ""
    var i = 0

    init { next() }

    fun next(): String {
        val prev = token
        token = ""
        while (i < s.length && s[i] <= ' ') i++ // skip spaces
        loop@while (i < s.length) {
            when (s[i]) {
                '{', '}' , ':', ',' -> {
                    if (token != "") break@loop
                    token += s[i++]
                    break@loop
                }
                in '\u0000'..' ' -> break@loop
                else -> token += s[i++]
            }
        }
        return prev
    }

    fun skip(s: String) {
        check(token == s)
        next()
    }
}
