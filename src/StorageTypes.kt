package storageTypes

import java.sql.*
import kotlin.reflect.*

interface ResultSetReader<out T> {
    fun ResultSet.read(): T
}

fun ResultSet.readInt(name: String): Int = getInt(name)
fun ResultSet.readString(name: String): String = getString(name)

@InjectSerializer
fun <T> ResultSet.read(reader: ResultSetReader<T>): T = with(reader) { read() }

// storage type annotation
annotation class Clob

// alternative storage type support
@SerializationQualifier(Clob::class)
fun ResultSet.readClob(name: String): String =
    with(getClob(name)) { getSubString(0, length().toInt()) }

@Serializable(ResultSetReader::class)
data class Person(
    val name: String,
    val age: Int,
    @Clob val notes: String
)

object __PersonResultSetReader : ResultSetReader<Person> {
    override fun ResultSet.read(): Person {
        val name = readString("name")
        val age = readInt("age")
        val notes = readClob("notes")
        return Person(name, age, notes)
    }
}

// --- test code ---

fun main(args: Array<String>) {
    val con: Connection = null!!
    val ps = con.prepareStatement("select * from Persons")
    val rs = ps.executeQuery()
    while (rs.next()) {
        val person = rs.read<Person>(__PersonResultSetReader)
        // ...
    }
}

// ---- aux annotations ---

annotation class Serializable(vararg val interfaces: KClass<*>)
annotation class SerializationQualifier(vararg val interfaces: KClass<*>)
annotation class InjectSerializer
