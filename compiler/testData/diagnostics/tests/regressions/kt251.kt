class A() {
    var x: Int = 0
        get() = <!TYPE_MISMATCH!>"s"<!>
        set(value: <!WRONG_SETTER_PARAMETER_TYPE!>String<!>) {
            field = <!TYPE_MISMATCH!>value<!>
        }
    val y: Int
        get(): <!WRONG_GETTER_RETURN_TYPE!>String<!> = <!TYPE_MISMATCH!>"s"<!>
    val z: Int
        get() {
            return <!TYPE_MISMATCH!>"s"<!>
        }

    var a: Any = 1
        set(v: <!WRONG_SETTER_PARAMETER_TYPE!>String<!>) {
            field = v
        }
    val b: Int
        get(): <!WRONG_GETTER_RETURN_TYPE!>Any<!> = <!TYPE_MISMATCH!>"s"<!>
    val c: Int
        get() {
            return 1
        }
    val d = 1
        get() {
            return field
        }
    val e = 1
        get(): <!WRONG_GETTER_RETURN_TYPE!>String<!> {
            return field
        }

}
