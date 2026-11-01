cmake_minimum_required(VERSION 3.24)

project(cnoise_native VERSION 0.0.1 LANGUAGES C)

include(CTest)

if(NOT CMAKE_SIZEOF_VOID_P EQUAL 8)
    message(FATAL_ERROR "C-Noise requires a 64-bit target.")
endif()

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS OFF)

add_library(cnoise SHARED
    src/core/cnoise.c
    src/kernels/perlin.c
    src/kernels/blended.c
)

target_include_directories(cnoise
    PUBLIC
        "${CMAKE_CURRENT_SOURCE_DIR}/include"
)

target_compile_definitions(cnoise PRIVATE CNOISE_BUILD)

set_target_properties(cnoise PROPERTIES
    C_VISIBILITY_PRESET hidden
    INTERPROCEDURAL_OPTIMIZATION FALSE
    WINDOWS_EXPORT_ALL_SYMBOLS FALSE
    OUTPUT_NAME cnoise
)

function(cnoise_strict_compilation target)
    if(MSVC)
        target_compile_options(${target} PRIVATE
            /W4
            /WX
            /fp:strict
            /utf-8
        )
    elseif(CMAKE_C_COMPILER_ID MATCHES "^(GNU|Clang|AppleClang)$")
        target_compile_options(${target} PRIVATE
            -Wall
            -Wextra
            -Wpedantic
            -Werror
            -fno-fast-math
            -ffp-contract=off
            -frounding-math
        )
    else()
        message(FATAL_ERROR
            "Compiler ${CMAKE_C_COMPILER_ID} has no verified strict-FP configuration."
        )
    endif()

    set_target_properties(${target} PROPERTIES
        INTERPROCEDURAL_OPTIMIZATION FALSE
    )
endfunction()

cnoise_strict_compilation(cnoise)

if(NOT WIN32)
    target_link_libraries(cnoise PRIVATE m)
endif()

if(BUILD_TESTING)
    # Experimental direct arithmetic; keep it outside the production ABI.
    # Экспериментальная прямая арифметика вне производственного ABI.
    add_library(cnoise_direct_benchmark SHARED
        tests/direct_arithmetic_benchmark.c
    )
    set_target_properties(cnoise_direct_benchmark PROPERTIES
        C_VISIBILITY_PRESET hidden
        WINDOWS_EXPORT_ALL_SYMBOLS FALSE
    )
    cnoise_strict_compilation(cnoise_direct_benchmark)
    if(NOT WIN32)
        target_link_libraries(cnoise_direct_benchmark PRIVATE m)
    endif()

    add_library(cnoise_perlin_test_bridge SHARED
        tests/perlin_test_bridge.c
        src/kernels/perlin.c
        src/kernels/blended.c
    )
    set_target_properties(cnoise_perlin_test_bridge PROPERTIES
        C_VISIBILITY_PRESET hidden
        WINDOWS_EXPORT_ALL_SYMBOLS FALSE
    )
    cnoise_strict_compilation(cnoise_perlin_test_bridge)
    if(NOT WIN32)
        target_link_libraries(cnoise_perlin_test_bridge PRIVATE m)
    endif()

    add_executable(cnoise_native_tests
        tests/test_core.c
    )

    target_link_libraries(cnoise_native_tests PRIVATE cnoise)
    cnoise_strict_compilation(cnoise_native_tests)

    if(NOT WIN32)
        target_link_libraries(cnoise_native_tests PRIVATE m)
    endif()

    add_test(
        NAME cnoise.native.core
        COMMAND cnoise_native_tests
    )
    set_tests_properties(cnoise.native.core PROPERTIES TIMEOUT 60)

    option(CNOISE_JAVA_TESTS "Build and register Java FFM integration tests" ON)
    if(CNOISE_JAVA_TESTS)
        find_package(Java 25 REQUIRED COMPONENTS Runtime Development)

        get_filename_component(CNOISE_PROJECT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/.." ABSOLUTE)
        set(CNOISE_JAVA_CLASSES "${CMAKE_CURRENT_BINARY_DIR}/java-test-classes")
        set(CNOISE_JAVA_STAMP "${CNOISE_JAVA_CLASSES}/compiled.stamp")
        set(CNOISE_RUNTIME "${CNOISE_PROJECT_ROOT}/src/main/java/com/Evga314/cnoise/runtime")
        set(CNOISE_JAVA_SOURCES
            "${CNOISE_RUNTIME}/bytecode/ArithmeticProgram.java"
            "${CNOISE_RUNTIME}/bridge/NativeArithmeticBridge.java"
            "${CNOISE_RUNTIME}/verification/VerifiedArithmeticExecution.java"
            "${CNOISE_PROJECT_ROOT}/tests/integration/ArithmeticBridgeTest.java"
            "${CNOISE_PROJECT_ROOT}/tests/integration/VerifiedArithmeticExecutionTest.java"
        )

        add_custom_command(
            OUTPUT "${CNOISE_JAVA_STAMP}"
            COMMAND "${CMAKE_COMMAND}" -E make_directory "${CNOISE_JAVA_CLASSES}"
            COMMAND "${Java_JAVAC_EXECUTABLE}"
                --release 25 -encoding UTF-8 -Xlint:all -Werror
                -d "${CNOISE_JAVA_CLASSES}" ${CNOISE_JAVA_SOURCES}
            COMMAND "${CMAKE_COMMAND}" -E touch "${CNOISE_JAVA_STAMP}"
            DEPENDS ${CNOISE_JAVA_SOURCES}
            COMMENT "Compiling standalone Java FFM integration tests"
            VERBATIM
        )
        add_custom_target(cnoise_java_tests ALL DEPENDS "${CNOISE_JAVA_STAMP}")
        add_dependencies(cnoise_java_tests cnoise)

        foreach(mode IN ITEMS interpreted default c2)
            set(jvm_options)
            if(mode STREQUAL "interpreted")
                list(APPEND jvm_options -Xint)
            elseif(mode STREQUAL "c2")
                list(APPEND jvm_options -Xbatch -XX:-TieredCompilation)
            endif()

            foreach(suite IN ITEMS parity gate)
                if(suite STREQUAL "parity")
                    set(test_class ArithmeticBridgeTest)
                else()
                    set(test_class VerifiedArithmeticExecutionTest)
                endif()
                set(test_name "cnoise.java.${suite}.${mode}")
                add_test(
                    NAME "${test_name}"
                    COMMAND "${Java_JAVA_EXECUTABLE}"
                        --enable-native-access=ALL-UNNAMED ${jvm_options}
                        -cp "${CNOISE_JAVA_CLASSES}" "${test_class}"
                        "$<TARGET_FILE:cnoise>"
                )
                set_tests_properties("${test_name}" PROPERTIES
                    TIMEOUT 120
                    LABELS "java;${suite};${mode}"
                )
            endforeach()
        endforeach()
    endif()
endif()

install(TARGETS cnoise
    RUNTIME DESTINATION bin
    LIBRARY DESTINATION lib
    ARCHIVE DESTINATION lib
)

install(FILES include/cnoise.h DESTINATION include)
