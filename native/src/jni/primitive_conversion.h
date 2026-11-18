#pragma once

namespace aevix::jni {

/**
 * Java reflection's permitted primitive identity and widening conversions (JLS 5.1.2).
 * Boolean only converts to itself; byte/short/char/int/long/float follow Java's widening rules.
 */
constexpr bool Widens(char from, char to) {
    if (from == to) return true;
    switch (from) {
    case 'B':
        return to == 'S' || to == 'I' || to == 'J' || to == 'F' || to == 'D';
    case 'S':
    case 'C':
        return to == 'I' || to == 'J' || to == 'F' || to == 'D';
    case 'I':
        return to == 'J' || to == 'F' || to == 'D';
    case 'J':
        return to == 'F' || to == 'D';
    case 'F':
        return to == 'D';
    default:
        return false;
    }
}

}  // namespace aevix::jni
