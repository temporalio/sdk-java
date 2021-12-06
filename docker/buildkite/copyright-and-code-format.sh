#!/bin/bash
set -xeou pipefail

./gradlew --no-daemon check -x test

if [ ! -z "$(git status --porcelain)" ]; then 
	echo 'Some files were improperly formatted. Please run build locally and check in all changes.'
	git status;
	exit 1;
fi

