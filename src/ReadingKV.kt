package readingKV

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

fun Parser.child(name: String) = this.also {
    skip(name)
    skip(":")
}

fun Parser.readString(): String = next()
fun Parser.readInt(): Int = next().toInt()

fun Parser.beginRead() { skip("{") }
fun Parser.nextRead() { skip(",") }
fun Parser.endRead() { skip("}") }

object __DateObjectNotationReader : ObjectNotationReader<Date> {
    override fun Parser.read(): Date {
        beginRead()
        val year = child("year").readInt()
        nextRead()
        val month = child("month").readInt()
        nextRead()
        val day = child("day").readInt()
        endRead()
        return Date(year, month, day)
    }
}

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

// --- test code ---

fun main(args: Array<String>) {
    println(parse("{name:Elvis,age:42,born:{year:1935,month:1,day:8},dead:{year:1977,month:8,day:16}}"))
    println(parse(" { name : Elvis, age : 42, born : { year : 1935 , month : 1 , day : 8 } , dead:{year:1977,month:8,day:16}}"))
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

