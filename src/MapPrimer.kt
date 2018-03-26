package mapPrimer

import kotlin.reflect.*

interface MapWriter<in T> {
    fun MutableMap<String,Any?>.write(obj: T)
}

interface MapReader<out T> {
    fun Map<String,Any?>.read(): T
}

interface MapIO<T> : MapWriter<T>, MapReader<T>

fun <T> MutableMap<String,Any?>.write(name: String, value: T) {
    this[name] = value
}

fun <T> Map<String,Any?>.read(name: String): T {
    check(contains(name)) { "Expected property '$name' is not found"}
    return this[name] as T
}

@Serializable(MapIO::class)
data class Person(val name: String, val age: Int)

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

@InjectSerializer
fun <T> MutableMap<String,Any?>.write(obj: T, writer: MapWriter<T>) = with(writer) { write(obj) }

@InjectSerializer
fun <T> Map<String,Any?>.read(reader: MapReader<T>): T = with(reader) { read() }

// --- test code ---

fun main(args: Array<String>) {
    val map = testWrite()
    testRead(map)
}

private fun testWrite(): Map<String,Any?> {
    val person = Person("Elvis", 42)
    val map = mutableMapOf<String,Any?>()
    map.write(person, __PersonMapIO)
    println(map)
    return map
}

private fun testRead(map: Map<String,Any?>) {
    val person = map.read(__PersonMapIO)
    println(person)
}


// ---- aux annotations ---

annotation class Serializable(vararg val interfaces: KClass<*>)
annotation class InjectSerializer
