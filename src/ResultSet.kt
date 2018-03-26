package resultSet

import java.sql.*
import kotlin.reflect.*

interface ResultSetReader<out T> {
    fun ResultSet.read(): T
}

fun ResultSet.readInt(name: String): Int = getInt(name)
fun ResultSet.readString(name: String): String = getString(name)

@InjectSerializer
fun <T> ResultSet.read(reader: ResultSetReader<T>): T = with(reader) { read() }

@Serializable(ResultSetReader::class)
data class Person(val name: String, val age: Int)

object __PersonResultSetReader : ResultSetReader<Person> {
    override fun ResultSet.read(): Person {
        val name = readString("name")
        val age = readInt("age")
        return Person(name, age)
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
annotation class InjectSerializer
