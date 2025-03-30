CC = gcc
CFLAGS = -Wall -g `pkg-config --cflags gtk+-3.0`
LIBS = `pkg-config --libs gtk+-3.0`

all: file_selector

file_selector: file_selector.c
	$(CC) -o file_selector file_selector.c $(CFLAGS) $(LIBS)

clean:
	rm -f file_selector
