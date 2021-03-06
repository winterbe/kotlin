package foo


open class A() {
    val value = "BAR"
}

interface Test {
    fun addFoo(s: String): String {
        return s + "FOO"
    }
}


class B() : Test, A() {
    fun eval(): String {
        return addFoo(value);
    }
}

fun box() = B().eval() == "BARFOO"