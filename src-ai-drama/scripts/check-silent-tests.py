#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
TD-2 守卫：扫描编译产物中的测试 .class 文件，拦截「带 @Test 注解、却编译为非 void 返回类型」
的方法。这类方法会被 JUnit Jupiter 静默丢弃——构建全绿、测试"通过"，但用例从未运行。

原理：javap -v -s 对每个方法输出
    public final void foo();
      descriptor: ()V
      ...
      RuntimeVisibleAnnotations:
        0: @org.junit.jupiter.api.Test
方法签名行在前，descriptor 行随后，@Test 注解在更靠后。按出现顺序跟踪「当前方法 + 返回类型」，
遇到 @Test 时若返回类型 != V 即判违规。

用法：
    python3 check-silent-tests.py <build-root-dir> [<build-root-dir> ...]

退出码：发现违规 -> 1；未发现 -> 0；参数错误 -> 2。
"""
import os
import re
import subprocess
import sys

# 注意：javap -v 输出的注解行形如「org.junit.jupiter.api.Test」（无前导 @），
# 故此处匹配注解类名本身，而非带 @ 的全限定写法。
TEST_ANNOTATIONS = (
    "org.junit.jupiter.api.Test",
    "org.junit.Test",
    "kotlin.test.Test",
)

# 方法签名行：缩进、含 '('、以 ';' 结尾，且不是 descriptor/字段注释行
METHOD_RE = re.compile(r"^\s{2,}.*\(\S*\)\s*;$")
# descriptor 行结尾的返回类型
RET_RE = re.compile(r"\)([A-Za-z0-9_$/\[\];]+)\s*$")
# 注解块结束的标志（遇到这些属性行说明 @Test 尚未出现且本方法属性已列完）
ANNO_END_MARKERS = (
    "descriptor", "Code", "flags", "Signature", "MethodParameters",
    "LineNumberTable", "LocalVariableTable", "LocalVariableTypeTable",
    "RuntimeVisible", "Exceptions", "AnnotationDefault", "Deprecated",
    "Synthetic", "Bridge", "Native", "StackMapTable", "ConstantValue",
    "InnerClasses", "PermittedSubclasses", "NestMembers", "NestHost",
)


def scan_class(path: str):
    try:
        out = subprocess.run(
            ["javap", "-v", "-s", path],
            capture_output=True, text=True, timeout=60,
        ).stdout
    except Exception as e:
        sys.stderr.write(f"[warn] javap 失败 {path}: {e}\n")
        return []

    violations = []
    cur_method = None
    cur_ret = None
    in_anno = False

    for raw in out.splitlines():
        s = raw.strip()
        if not s:
            continue

        # 新方法签名：重置当前方法上下文
        if METHOD_RE.match(raw) and not s.startswith("descriptor"):
            m = re.search(r"(\w+)\s*\(", s)
            if m:
                cur_method = m.group(1)
                cur_ret = None
                in_anno = False
                continue

        if s.startswith("descriptor:"):
            dm = RET_RE.search(s)
            if dm:
                cur_ret = dm.group(1)
            continue

        if "RuntimeVisibleAnnotations" in s or "RuntimeInvisibleAnnotations" in s:
            in_anno = True
            continue

        if in_anno:
            if any(s.startswith(mk) for mk in ANNO_END_MARKERS):
                in_anno = False
            elif any(a in s for a in TEST_ANNOTATIONS):
                if cur_method and cur_ret and cur_ret != "V":
                    violations.append((path, cur_method, cur_ret))
                # 每个方法仅一个 @Test 注解块，命中即清空，避免重复
                cur_method = None
                in_anno = False
                continue
    return violations


def collect_class_files(roots):
    files = []
    for root in roots:
        if not os.path.isdir(root):
            sys.stderr.write(f"[warn] 目录不存在，跳过: {root}\n")
            continue
        for dirpath, _, names in os.walk(root):
            # 跳过 main 源集（主代码绝不含 @Test，省去大半开销）
            if "/main/" in dirpath.replace("\\", "/"):
                continue
            for n in names:
                if n.endswith(".class"):
                    files.append(os.path.join(dirpath, n))
    return files


def main():
    if len(sys.argv) < 2:
        sys.stderr.write("用法: python3 check-silent-tests.py <build-dir> [<build-dir> ...]\n")
        return 2

    class_files = collect_class_files(sys.argv[1:])
    if not class_files:
        sys.stderr.write("[warn] 未找到任何测试 .class 文件，守卫跳过（视为通过）。\n")
        return 0

    violations = []
    for cf in class_files:
        try:
            violations.extend(scan_class(cf))
        except Exception as e:
            sys.stderr.write(f"[warn] 扫描失败 {cf}: {e}\n")

    if violations:
        sys.stderr.write("\n")
        sys.stderr.write("FAIL: 发现 %d 个 @Test 编译为非 void，JUnit Jupiter 会静默丢弃：\n" % len(violations))
        for path, method, ret in violations:
            sys.stderr.write(f"  method: {method}()  desc: ({ret})  <-- 期望以 )V 结尾\n")
            sys.stderr.write(f"          file:   {path}\n")
        return 1

    sys.stderr.write(f"OK: 已扫描 {len(class_files)} 个测试 class，无被静默丢弃的非 void @Test。\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
