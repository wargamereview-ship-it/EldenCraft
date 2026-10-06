#!/bin/sh
package=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
"$package/Install.sh" "$@"
result=$?
if [ "$result" -ne 0 ]; then printf 'Press Enter to close. '; read -r answer; fi
exit "$result"
