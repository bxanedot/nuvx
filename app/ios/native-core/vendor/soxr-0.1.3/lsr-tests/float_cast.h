/*
** Copyright (C) 2001-2011 Erik de Castro Lopo <erikd@mega-nerd.com>
**
** This program is free software; you can redistribute it and/or modify
** it under the terms of the GNU Lesser General Public License as published by
** the Free Software Foundation; either version 2.1 of the License, or
** (at your option) any later version.
**
** This program is distributed in the hope that it will be useful,
** but WITHOUT ANY WARRANTY; without even the implied warranty of
** MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
** GNU Lesser General Public License for more details.
**
** You should have received a copy of the GNU Lesser General Public License
** along with this program; if not, write to the Free Software
** Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
*/



#ifndef FLOAT_CAST_HEADER
#define FLOAT_CAST_HEADER

























#include "config.h"







#define		HAVE_LRINT_REPLACEMENT	0

#if (HAVE_LRINT && HAVE_LRINTF)









	#define	_ISOC9X_SOURCE	1
	#define _ISOC99_SOURCE	1

	#define	__USE_ISOC9X	1
	#define	__USE_ISOC99	1

	#include	<math.h>

#elif (defined (__CYGWIN__))

	#include	<math.h>

	#undef		HAVE_LRINT_REPLACEMENT
	#define		HAVE_LRINT_REPLACEMENT	1

	#undef	lrint
	#undef	lrintf

	#define	lrint	double2int
	#define	lrintf	float2int










	static inline long double2int (double in)
	{	long retval ;

		__asm__ __volatile__
		(	"fistpl %0"
			: "=m" (retval)
			: "t" (in)
			: "st"
			) ;

		return retval ;
	}  

	static inline long float2int (float in)
	{	long retval ;

		__asm__ __volatile__
		(	"fistpl %0"
			: "=m" (retval)
			: "t" (in)
			: "st"
			) ;

		return retval ;
	}  

#elif (defined (WIN64) || defined(_WIN64))





	#include	<math.h>




	#include    <emmintrin.h>
	#include    <mmintrin.h>

	__inline long int
	lrint(double flt)
	{
		return _mm_cvtsd_si32(_mm_load_sd(&flt));
	}

	__inline long int
	lrintf(float flt)
	{
		return _mm_cvtss_si32(_mm_load_ss(&flt));
	}

#elif (defined (WIN32) || defined (_WIN32))

	#undef		HAVE_LRINT_REPLACEMENT
	#define		HAVE_LRINT_REPLACEMENT	1

	#include	<math.h>






	__inline long int
	lrint (double flt)
	{	int intgr ;

		_asm
		{	fld flt
			fistp intgr
			} ;

		return intgr ;
	}

	__inline long int
	lrintf (float flt)
	{	int intgr ;

		_asm
		{	fld flt
			fistp intgr
			} ;

		return intgr ;
	}

#elif (defined (__MWERKS__) && defined (macintosh))



	#undef		HAVE_LRINT_REPLACEMENT
	#define		HAVE_LRINT_REPLACEMENT	1
	#include	<math.h>

	#undef	lrint
	#undef	lrintf

	#define	lrint	double2int
	#define	lrintf	float2int

	inline int
	float2int (register float in)
	{	long res [2] ;

		asm
		{	fctiw	in, in
			stfd	 in, res
		}
		return res [1] ;
	}  

	inline int
	double2int (register double in)
	{	long res [2] ;

		asm
		{	fctiw	in, in
			stfd	 in, res
		}
		return res [1] ;
	}  

#elif (defined (__MACH__) && defined (__APPLE__))



	#undef		HAVE_LRINT_REPLACEMENT
	#define		HAVE_LRINT_REPLACEMENT	1
	#include	<math.h>

	#undef lrint
	#undef lrintf

	#define lrint	double2int
	#define lrintf	float2int

	inline static long
	float2int (register float in)
	{	int res [2] ;

		__asm__ __volatile__
		(	"fctiw	%1, %1\n\t"
			"stfd	%1, %0"
			: "=m" (res)	 
			: "f" (in)		 
			: "memory"
			) ;

		return res [1] ;
	}  

	inline static long
	double2int (register double in)
	{	int res [2] ;

		__asm__ __volatile__
		(	"fctiw	%1, %1\n\t"
			"stfd	%1, %0"
			: "=m" (res)	 
			: "f" (in)		 
			: "memory"
			) ;

		return res [1] ;
	}  

#else
	#ifndef __sgi
	#warning "Don't have the functions lrint() and lrintf()."
	#warning "Replacing these functions with a standard C cast."
	#endif

	#include	<math.h>

	#define	lrint(dbl)		((long) (dbl))
	#define	lrintf(flt)		((long) (flt))

#endif


#endif  

