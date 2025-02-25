import sys
import time

def matrixCalc(col,matrix1, matrix2):
    matrix3 = []
    for i in range(col):
        for j in range(col):
            temp = 0
            for k in range(col):
                temp += matrix1[i*col+k] * matrix2[k*col+j]
            matrix3.append(temp)
    return matrix3

def matrixLine(col,matrix1,matrix2):
    matrix3 = [0] * (col * col)
    for i in range(col):
        for k in range(col):
            temp = matrix1[i*col+k]
            for j in range(col):
                matrix3[i*col+j] += temp*matrix2[k*col+j]
    return matrix3

def main(op, col):
    if op != 1 and op != 2:
        print("Bad Input, Wrong Operation, please use 1 or 2")
        return 
    
    matrix1 = []
    matrix2 = []

    for i in range(col):    
        for j in range(col):
            matrix1.append(float(1.0))

    for i in range(col):    
        for j in range(col):
            matrix2.append(float(i+1.0))

    start = time.time()
    if op == 1:
        matrix = matrixCalc(col,matrix1,matrix2)
    else:
        matrix = matrixLine(col,matrix1,matrix2)
    end = time.time()
    print(f"First value of matrix : {matrix[0]}")
    print(f"Time elapsed in seconds : {end-start}")
    
if __name__ == "__main__":
    op = int(sys.argv[1])
    col = int(sys.argv[2])
    main(op,col)