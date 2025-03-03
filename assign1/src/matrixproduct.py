import sys
import time

def matrixCalc(col, matrix1, matrix2):
    matrix3 = []
    for i in range(col):
        for j in range(col):
            temp = 0
            for k in range(col):
                temp += matrix1[i * col + k] * matrix2[k * col + j]
            matrix3.append(temp)
    return matrix3

def matrixLine(col, matrix1, matrix2):
    matrix3 = [0] * (col * col)
    for i in range(col):
        for k in range(col):
            temp = matrix1[i * col + k]
            for j in range(col):
                matrix3[i * col + j] += temp * matrix2[k * col + j]
    return matrix3

import sys
import time

def matrixCalc(col, matrix1, matrix2):
    matrix3 = []
    for i in range(col):
        for j in range(col):
            temp = 0
            for k in range(col):
                temp += matrix1[i * col + k] * matrix2[k * col + j]
            matrix3.append(temp)
    return matrix3

def matrixLine(col, matrix1, matrix2):
    matrix3 = [0] * (col * col)
    for i in range(col):
        for k in range(col):
            temp = matrix1[i * col + k]
            for j in range(col):
                matrix3[i * col + j] += temp * matrix2[k * col + j]
    return matrix3

def main(op, col):
    if op not in ["standard", "line"]:
        print("Bad Input, Wrong Operation, please use 'standard' or 'line'")
        return

    matrix1 = []
    matrix2 = []

    for i in range(col):
        for j in range(col):
            matrix1.append(float(1.0))

    for i in range(col):
        for j in range(col):
            matrix2.append(float(i + 1.0))

    start = time.time()
    if op == "standard":
        matrix = matrixCalc(col, matrix1, matrix2)
    else:
        matrix = matrixLine(col, matrix1, matrix2)
    end = time.time()

    print(f"First value of matrix : {matrix[0]}")
    print(f"Time elapsed in seconds : {end-start}")

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: python3 matrixproduct.py <standard/line> <matrix_size>")
        sys.exit(1)

    op = sys.argv[1]
    col = int(sys.argv[2])
    main(op, col)
