cmake_minimum_required(VERSION 3.22.1)
project(etxvk C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

option(ETX_ADRENOTOOLS "Load custom Vulkan drivers (Turnip) through libadrenotools" ON)

add_library(etxvk SHARED vkprobe.cpp)
target_link_libraries(etxvk log dl)

if(ETX_ADRENOTOOLS)
    include(FetchContent)
    FetchContent_Declare(
        adrenotools
        GIT_REPOSITORY https://github.com/bylaws/libadrenotools.git
        GIT_TAG master
        GIT_SHALLOW TRUE
    )
    FetchContent_MakeAvailable(adrenotools)
    target_link_libraries(etxvk adrenotools)
    target_compile_definitions(etxvk PRIVATE ETX_ADRENOTOOLS=1)
endif()
