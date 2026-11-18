#include <cassert>
#include "jni/primitive_conversion.h"

int main() {
    using aevix::jni::Widens;
    constexpr char types[] = {'Z', 'B', 'C', 'S', 'I', 'J', 'F', 'D'};
    for (char type : types) assert(Widens(type, type));

    assert(Widens('B', 'S'));
    assert(Widens('B', 'I'));
    assert(Widens('B', 'J'));
    assert(Widens('B', 'F'));
    assert(Widens('B', 'D'));
    assert(Widens('C', 'I'));
    assert(Widens('S', 'D'));
    assert(Widens('I', 'J'));
    assert(Widens('J', 'D'));
    assert(Widens('F', 'D'));

    assert(!Widens('Z', 'I'));
    assert(!Widens('C', 'S'));
    assert(!Widens('S', 'B'));
    assert(!Widens('I', 'C'));
    assert(!Widens('J', 'I'));
    assert(!Widens('D', 'F'));
    assert(!Widens('F', 'J'));
    return 0;
}
