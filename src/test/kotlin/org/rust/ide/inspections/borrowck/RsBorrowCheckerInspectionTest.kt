/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.inspections.borrowck

import org.rust.ProjectDescriptor
import org.rust.WithStdlibRustProjectDescriptor
import org.rust.ide.inspections.RsBorrowCheckerInspection
import org.rust.ide.inspections.RsInspectionsTestBase

class RsBorrowCheckerInspectionTest : RsInspectionsTestBase(RsBorrowCheckerInspection()) {

    fun `test mutable used at ref mutable method call (self)`() = checkByText("""
        struct S;
        impl S {
            fn test(&mut self) {}
        }
        fn main() {
            let mut test = S;
            test.test();
        }
    """, checkWarn = false)

    fun `test mutable used at mutable method call (self)`() = checkByText("""
        struct S;
        impl S {
            fn test(mut self) {}
        }
        fn main() {
            let test = S;
            test.test();
        }
    """, checkWarn = false)

    fun `test mutable used at mutable method call (args)`() = checkByText("""
        struct S;
        impl S {
            fn test(&self, test: &mut S) {}
        }
        fn main() {
            let test = S;
            let mut reassign = S;
            test.test(&mut reassign);
        }
    """, checkWarn = false)

    fun `test mutable used at mutable call`() = checkByText("""
        struct S;
        fn test(test: &mut S) {}
        fn main() {
            let mut s = S;
            test(&mut s);
        }
    """, checkWarn = false)

    fun `test immutable used at mutable call (pattern)`() = checkByText("""
        struct S;
        impl S {
            fn foo(&mut self) {}
        }
        fn test((x,y): (&S, &mut S)) {
            <error>x</error>.foo();
        }
    """, checkWarn = false)

    fun `test mutable used at mutable call (pattern)`() = checkByText("""
        struct S;
        impl S {
            fn foo(&mut self) {}
        }
        fn test((x,y): (&mut S, &mut S)) {
            x.foo();
        }
    """, checkWarn = false)

    fun `test mutable used at mutable method definition`() = checkByText("""
        struct S;
        impl S {
            fn test(&mut self) {
                self.foo();
            }
            fn foo(&mut self) {}
        }
    """, checkWarn = false)

    fun `test mutable type should not annotate`() = checkByText("""
        struct S;
        impl S {
            fn foo(&mut self) {}
        }
        trait Test {
            fn test(self);
        }
        impl<'a> Test for &'a mut S {
            fn test(self) {
                self.foo();
            }
        }
    """, checkWarn = false)

    fun `test immutable used at mutable method definition`() = checkByText("""
        struct S;
        impl S {
            fn test(&self) {
                <error descr="Cannot borrow immutable local variable `self` as mutable">self</error>.foo();
            }
            fn foo(&mut self) {}
        }
    """, checkWarn = false)

    fun `test immutable used at reference mutable function definition`() = checkByText("""
        struct S;
        impl S {
            fn foo(&mut self) {}
        }
        fn test(test: &S) {
            <error>test</error>.foo();
        }
    """, checkWarn = false)

    fun `test mutable used at reference mutable function definition`() = checkByText("""
        struct S;
        impl S {
            fn foo(&mut self) {}
        }
        fn test(test: &mut S) {
            test.foo();
        }
    """, checkWarn = false)

    fun `test no highlight for mutable for loops`() = checkByText("""
        fn test() {
            let mut xs: Vec<Vec<usize>> = vec![vec![1, 2], vec![3, 4]];
            for test in &mut xs {
                test.push(0);
            }
        }
    """, checkWarn = false)

    fun `test immutable used at reassign`() = checkByText("""
        fn main() {
            let a;
            if true {
                a = 10;
            } else {
                a = 20;
            }
            a = 5;//FIXME(farodin91): this line should fail
        }
    """, checkWarn = false)

    fun `test mutable used at reassign`() = checkByText("""
        fn main() {
            let mut x;
            x = 3;
            x = 5;
        }
    """, checkWarn = false)

    fun `test let some from mutable reference`() = checkByText("""
        fn foo(optional: Option<&mut String>) {
            if let Some(x) = optional {
                *x = "str".to_string();
            }
        }
    """, checkWarn = false)

    fun `test simple enum variant is treated as mutable`() = checkByText("""
        enum Foo { FOO }
        fn foo (f: &mut Foo) {}
        fn bar () {
            foo(&mut Foo::FOO);     // Must not be highlighted
        }
    """, checkWarn = false)

    fun `test mutable reference to empty struct with and without braces`() = checkByText("""
        struct S;

        fn main() {
            let test1 = &mut S; // Must not be highlighted
            let test2 = &mut S {}; // Must not be highlighted
        }
    """, checkWarn = false)

    fun `test immutable used at ref mutable method call (self)`() = checkByText("""
        struct S;
        impl S {
            fn test(&mut self) {}
        }
        fn main() {
            let test = S;
            <error>test</error>.test();
        }
    """, checkWarn = false)

    fun `test immutable used at mutable method call (args)`() = checkByText("""
        struct S;
        impl S {
            fn test(&self, test: &mut S) {}
        }
        fn main() {
            let test = S;
            let reassign = S;
            test.test(&mut <error descr="Cannot borrow immutable local variable `reassign` as mutable">reassign</error>);
        }
    """, checkWarn = false)

    fun `test immutable used at mutable call`() = checkByText("""
        struct S;
        fn test(test: &mut S) {}
        fn main() {
            let s = S;
            test(&mut <error>s</error>);
        }
    """, checkWarn = false)

    fun `test &mut on function`() = checkByText("""
        fn foo() {}

        fn main() {
            let local = &mut foo;
        }
    """, checkWarn = false)

    fun `test &mut on method`() = checkByText("""
        struct A {}
        impl A {
            fn foo(&mut self) {}
        }

        fn main() {
            let local = &mut A::foo;
        }
    """, checkWarn = false)

    @ProjectDescriptor(WithStdlibRustProjectDescriptor::class)
    fun `test rvalue method call`() = checkByText("""
        fn main() {
            let v = vec![1];
            v.iter().any(|c| *c == 2);
        }
    """, checkWarn = false)

    fun `test rvalue if expr`() = checkByText("""
        struct S {}
        impl S {
            fn f_mut(&mut self) { }
        }
        fn main() {
          (if true { S } else { S }).f_mut();
        }
    """, checkWarn = false)

    fun `test tuple`() = checkByText("""
        fn main() {
            let mut x = (0, 0);
            let y = &mut x;
            let _z = &mut y.0;
        }
    """)

    fun `test tuple multiple deref`() = checkByText("""
        fn main() {
            let mut x = (0, 0);
            let y = &mut & x;
            let _z = &mut <error descr="Cannot borrow immutable local variable `y.0` as mutable">y.0</error>;
        }
    """)

    /** [See github issue 2711](https://github.com/intellij-rust/intellij-rust/issues/2711) */
    @ProjectDescriptor(WithStdlibRustProjectDescriptor::class)
    fun `test vector index`() = checkByText("""
        fn f() {
            let mut a = vec![1];
            let b = &mut a;
            let c = &mut b[0];
        }
    """, checkWarn = false)

    /** Issue [3914](https://github.com/intellij-rust/intellij-rust/issues/3914) */
    fun `test closure borrow parameter of unknown type as mutable`() = checkByText("""
        fn main() {
            |x| &mut x;
        }
    """)
}
